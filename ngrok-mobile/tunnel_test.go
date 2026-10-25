package ngrokmobile

import (
	"strings"
	"testing"
)

// Needs network access to connect.ngrok-agent.com; a bogus token must fail fast with an AUTH: error.
func TestBogusTokenRejected(t *testing.T) {
	_, err := Start("bogus00000000000000000000000000000000", 1, "test")
	if err == nil || !strings.HasPrefix(err.Error(), "AUTH:") {
		t.Fatalf("expected AUTH error, got %v", err)
	}
	t.Log(err)
}
