package com.ouor.mobilegit.core

import kotlinx.serialization.Serializable

// PRD 6장 데이터 모델. 시각은 모두 epoch millis(UTC).

@Serializable enum class RepoType { MIRROR, LOCAL }
@Serializable enum class CreatedBy { APP_EMPTY, PUSH, MIRROR_ADD, BULK, FORK }
@Serializable enum class OriginStatus { OK, GONE, AUTH_REQUIRED, UNKNOWN }
@Serializable enum class WaitingReason { NONE, WIFI, CHARGING, STORAGE }
@Serializable enum class SyncTrigger { MANUAL, AUTO, BULK, INITIAL }
@Serializable enum class SyncKind { CHECK, UPDATE }
@Serializable enum class SyncResult { NO_CHANGE, SUCCESS, FAILED, WAITING }

/** F-12 AC3 실패 분류. */
@Serializable enum class ErrorCategory { NETWORK, AUTH_REQUIRED, ORIGIN_GONE, STORAGE_FULL, REMOTE_ERROR, TIMEOUT }

/** F-05 AC2 상태 배지. */
@Serializable enum class Badge { UPDATE_AVAILABLE, UPDATING, FAILED, ORIGIN_GONE, AUTH_REQUIRED, DELAYED, LFS_MISSING,
    /** R2-01 AC2: an update archived refs (F-11); cleared when the user opens the archive screen ([RepoStore.markHistorySeen]). */
    HISTORY_CHANGED }

@Serializable
data class MirrorSource(
    val remoteUrl: String,
    val credentialId: String? = null,
    val autoUpdateExcluded: Boolean = false,
    val originStatus: OriginStatus = OriginStatus.UNKNOWN,
    val lastCheckAt: Long? = null,
    val lastCheckResult: SyncResult? = null,
    val lastUpdateAt: Long? = null,
    val lastSuccessUpdateAt: Long? = null,
    val pendingChanges: Int = 0,
    val waitingReason: WaitingReason = WaitingReason.NONE,
    val consecutiveFailures: Int = 0,
    /** 자동 확인/업데이트 백오프: 이 시각 전에는 자동 작업을 건너뛴다 (수동은 무시). */
    val nextRetryAt: Long? = null,
    val lastError: ErrorCategory? = null,
    /** 첫 받기가 한 번이라도 성공했는가. false면 레포가 비어 있다. */
    val initialFetchDone: Boolean = false,
    /** CR-2: mirror review refs (refs/pull/..., refs/merge-requests/...). null = follow [Settings.mirrorPullRefs]. */
    val includePullRefs: Boolean? = null,
    /**
     * GIT-08: the first fetch was not started because Android did not let the worker run in the foreground (app in
     * background, Android 12+). It starts when the app is opened ([SyncCoordinator.resumeWaitingForForeground]).
     */
    val waitingForForeground: Boolean = false,
    /** R2-02 AC3: last check or update that reached the origin. [lastCheckAt] also moves on failures. */
    val lastSuccessCheckAt: Long? = null,
    /** R2-01 AC1: start of the current run of failures ("3일째"); null while healthy. */
    val failingSince: Long? = null,
) {
    /** Last successful contact with the origin; for data written before [lastSuccessCheckAt] existed, a best guess. */
    val lastOkAt: Long? get() = lastSuccessCheckAt ?: lastCheckAt?.takeIf { lastCheckResult != SyncResult.FAILED } ?: lastSuccessUpdateAt
}

@Serializable
data class RepoMeta(
    val id: String,
    val name: String,
    val type: RepoType,
    val description: String = "",
    val createdAt: Long,
    val createdBy: CreatedBy,
    val defaultBranch: String? = null,
    val sizeBytes: Long = 0,
    val sizeMeasuredAt: Long? = null,
    val hasLfs: Boolean = false,
    val submoduleUrls: List<String> = emptyList(),
    val lastPushAt: Long? = null,
    val lastPushIp: String? = null,
    val forkedFromUrl: String? = null,
    val forkedAt: Long? = null,
    val archivedRefCount: Int = 0,
    val mirror: MirrorSource? = null,
    /** R2-01 AC2: when the last update archived refs and the user has not opened the archive screen since. */
    val historyChangedAt: Long? = null,
)

@Serializable
data class RefChange(val ref: String, val oldSha: String?, val newSha: String?, val archivedAs: String? = null) {
    /**
     * R2-04 AC1. Derived, so history written by older versions gets it too: non-fast-forward branch updates and
     * deletions are always archived ([MirrorEngine]), so an unarchived branch update is a fast-forward.
     * Review refs (refs/pull/...) are never archived and always count as [RefChangeKind.FAST_FORWARD] when moved.
     */
    val kind: RefChangeKind get() {
        val tag = ref.startsWith("refs/tags/")
        return when {
            oldSha == null -> RefChangeKind.CREATED
            newSha == null -> if (tag) RefChangeKind.TAG_DELETED else RefChangeKind.DELETED
            tag -> RefChangeKind.TAG_MOVED
            archivedAs != null -> RefChangeKind.FORCED
            else -> RefChangeKind.FAST_FORWARD
        }
    }
}

/** R2-04 / R2-03: "새 커밋", "히스토리 바뀜(강제 push)", "새 브랜치·태그", "브랜치 삭제됨", "태그 이동됨", "태그 삭제됨". */
@Serializable enum class RefChangeKind { FAST_FORWARD, FORCED, CREATED, DELETED, TAG_MOVED, TAG_DELETED }

@Serializable
data class SyncEvent(
    val startedAt: Long,
    val durationMs: Long,
    val trigger: SyncTrigger,
    val kind: SyncKind,
    val result: SyncResult,
    val refChanges: List<RefChange> = emptyList(),
    val newCommitCount: Int = 0,
    val archivedRefCount: Int = 0,
    val errorCategory: ErrorCategory? = null,
    /** 토큰/자격 증명은 제거된 원문 오류 (F-07 AC5). */
    val errorDetail: String? = null,
    val waitingReason: WaitingReason? = null,
)

@Serializable
data class RemoteCredential(
    val id: String,
    val host: String,
    val username: String? = null,
    /** SecretCipher로 암호화된 토큰. 화면에는 [tokenPrefix]만 쓴다. */
    val encryptedToken: String,
    val tokenPrefix: String,
    val label: String = "",
    val createdAt: Long,
)

@Serializable enum class BulkSourceKind { GITHUB_USER, GITHUB_ORG, GITLAB_USER, GITLAB_GROUP, URL_LIST }
@Serializable enum class BulkItemStatus { QUEUED, RUNNING, DONE, FAILED }
@Serializable enum class BulkJobStatus { RUNNING, DONE }

@Serializable
data class BulkItem(val remoteUrl: String, val repoName: String, val status: BulkItemStatus = BulkItemStatus.QUEUED, val error: String? = null)

@Serializable
data class BulkJob(
    val id: String,
    val source: BulkSourceKind,
    val account: String = "",
    val host: String = "",
    val createdAt: Long,
    val items: List<BulkItem>,
    /** 원격 API/호스트 레이트리밋 대기 중이면 재시도 가능 시각 (F-15 AC7). */
    val rateLimitedUntil: Long? = null,
) {
    val status: BulkJobStatus get() = if (items.any { it.status == BulkItemStatus.QUEUED || it.status == BulkItemStatus.RUNNING }) BulkJobStatus.RUNNING else BulkJobStatus.DONE
    fun count(s: BulkItemStatus) = items.count { it.status == s }
}

@Serializable enum class AccessRoute { WIFI, HOTSPOT, USB_TETHER, TAILSCALE, NGROK, LOCALHOST, OTHER }
@Serializable enum class AccessAction { READ, PUSH, WEB }
@Serializable enum class AuthResult { OK, FAILED, ANONYMOUS, BLOCKED }

@Serializable
data class AccessLogEntry(
    val at: Long,
    val clientIp: String,
    val route: AccessRoute,
    val repoName: String?,
    val action: AccessAction,
    val authResult: AuthResult,
    val status: Int,
)

/**
 * CR-7: one ref updated by an accepted push to a local repo. [oldSha] null = created, [newSha] null = deleted.
 * [ref] is the full name (refs/heads/main, refs/tags/v1).
 */
@Serializable
data class PushEvent(
    val at: Long,
    val ref: String,
    val oldSha: String?,
    val newSha: String?,
    val route: AccessRoute,
    val clientIp: String,
    val forced: Boolean = false,
) {
    /** Branch name for refs/heads/..., else null. */
    val branch: String? get() = ref.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
}

/** F-02 주소 종류. 우선순위는 enum 순서 (와이파이 → 핫스팟 → USB 테더링 → Tailscale → ngrok, R2-15). 블루투스 테더링은 HOTSPOT. */
enum class AddressKind { WIFI, HOTSPOT, USB_TETHER, TAILSCALE, NGROK }

data class ServerAddress(val kind: AddressKind, val host: String, val baseUrl: String) {
    fun cloneUrl(repo: String) = "$baseUrl/$repo.git"
}
