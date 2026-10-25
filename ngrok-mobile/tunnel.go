// Package ngrokmobile is a tiny gomobile-friendly wrapper around ngrok-go (MIT) used by the mobilegit Android app
// for F-04 "외부 접속(ngrok)". ngrok-java's Android native build is not loadable on ART (ngrok/ngrok-java#54),
// so the tunnel is embedded through gomobile instead.
package ngrokmobile

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"golang.ngrok.com/ngrok/v2"
)

// Tunnel is one HTTP endpoint forwarding to a local port.
type Tunnel struct {
	agent  ngrok.Agent
	fwd    ngrok.EndpointForwarder
	cancel context.CancelFunc
}

// Start connects with authtoken and forwards a random https URL to http://127.0.0.1:<localPort>.
// ngrok-go retries the first connect forever, so the first failure (or a 30s timeout) aborts it here and
// the app's TunnelManager owns retry/backoff. Errors starting with "AUTH:" mean the authtoken was rejected.
func Start(authtoken string, localPort int, clientVersion string) (*Tunnel, error) {
	ctx, cancel := context.WithCancel(context.Background())
	var mu sync.Mutex
	connected := false
	var firstErr error
	fail := func(err error) {
		mu.Lock()
		defer mu.Unlock()
		if !connected && firstErr == nil {
			firstErr = err
			cancel()
		}
	}
	agent, err := ngrok.NewAgent(
		ngrok.WithAuthtoken(authtoken),
		ngrok.WithClientInfo("mobilegit", clientVersion),
		ngrok.WithEventHandler(func(e ngrok.Event) {
			switch ev := e.(type) {
			case *ngrok.EventAgentConnectSucceeded:
				mu.Lock()
				connected = true
				mu.Unlock()
			case *ngrok.EventAgentDisconnected:
				if ev.Error != nil {
					fail(ev.Error)
				}
			}
		}),
	)
	if err != nil {
		cancel()
		return nil, classify(err)
	}
	timer := time.AfterFunc(30*time.Second, func() { fail(errors.New("timed out connecting to ngrok")) })
	defer timer.Stop()
	if err := agent.Connect(ctx); err != nil {
		cancel()
		mu.Lock()
		if firstErr != nil {
			err = firstErr
		}
		mu.Unlock()
		return nil, classify(err)
	}
	fwd, err := agent.Forward(ctx, ngrok.WithUpstream(fmt.Sprintf("http://127.0.0.1:%d", localPort)))
	if err != nil {
		cancel()
		_ = agent.Disconnect()
		return nil, classify(err)
	}
	return &Tunnel{agent: agent, fwd: fwd, cancel: cancel}, nil
}

func classify(err error) error {
	var ne ngrok.Error
	code := ""
	if errors.As(err, &ne) {
		code = ne.Code()
	}
	msg := err.Error()
	switch code {
	case "ERR_NGROK_105", "ERR_NGROK_106", "ERR_NGROK_107", "ERR_NGROK_4018":
		return fmt.Errorf("AUTH:%s: %s", code, msg)
	}
	if strings.Contains(strings.ToLower(msg), "authtoken") || strings.Contains(strings.ToLower(msg), "authentication failed") {
		return fmt.Errorf("AUTH:%s", msg)
	}
	return err
}

// URL is the public https URL.
func (t *Tunnel) URL() string { return t.fwd.URL().String() }

// AwaitDone blocks until the endpoint stops (agent disconnected or Close called).
func (t *Tunnel) AwaitDone() { <-t.fwd.Done() }

// Close stops forwarding and disconnects the agent.
func (t *Tunnel) Close() {
	t.cancel()
	_ = t.fwd.Close()
	_ = t.agent.Disconnect()
}
