// sftpssh supplies the SSH byte stream used by restic's SFTP backend.
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

func generateIdentity() error {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	pubKey, err := ssh.NewPublicKey(pub)
	if err != nil {
		return err
	}
	fmt.Printf("%s %s", base64.StdEncoding.EncodeToString(key.Seed()), ssh.MarshalAuthorizedKey(pubKey))
	return nil
}

var probeComplete = errors.New("host key received")

func connect(probe bool) error {
	address := os.Getenv("RD_SSH_ADDRESS")
	config := &ssh.ClientConfig{User: os.Getenv("RD_SSH_USER")}
	config.HostKeyCallback = func(_ string, _ net.Addr, key ssh.PublicKey) error {
		pin := address + " " + ssh.FingerprintSHA256(key)
		if probe {
			fmt.Println(pin)
			return probeComplete // stop before authentication
		}
		if pin != os.Getenv("RD_SSH_PIN") {
			return errors.New("SSH host key changed or unapproved. Verify the fingerprint in repository settings")
		}
		return nil
	}
	if !probe {
		seedText, _, _ := strings.Cut(os.Getenv("RD_SSH_IDENTITY"), " ")
		seed, err := base64.StdEncoding.DecodeString(seedText)
		if err != nil || len(seed) != ed25519.SeedSize {
			return errors.New("invalid SSH identity")
		}
		signer, err := ssh.NewSignerFromKey(ed25519.NewKeyFromSeed(seed))
		if err != nil {
			return err
		}
		config.Auth = []ssh.AuthMethod{ssh.PublicKeys(signer)}
	}
	conn, err := net.DialTimeout("tcp", address, 15*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(20 * time.Second)); err != nil {
		return err
	}
	c, chans, reqs, err := ssh.NewClientConn(conn, address, config)
	if probe && errors.Is(err, probeComplete) {
		return nil
	}
	if err != nil {
		return err
	}
	client := ssh.NewClient(c, chans, reqs)
	defer client.Close()
	channel, requests, err := client.OpenChannel("session", nil)
	if err != nil {
		return err
	}
	defer channel.Close()
	go ssh.DiscardRequests(requests)
	ok, err := channel.SendRequest("subsystem", true, ssh.Marshal(struct{ Name string }{"sftp"}))
	if err != nil {
		return err
	}
	if !ok {
		return errors.New("server refused the SFTP subsystem")
	}
	if err := conn.SetDeadline(time.Time{}); err != nil {
		return err
	}
	go func() {
		_, _ = io.Copy(channel, os.Stdin)
		_ = channel.CloseWrite()
	}()
	go io.Copy(os.Stderr, channel.Stderr())
	_, err = io.Copy(os.Stdout, channel)
	return err
}

func run() error {
	if len(os.Args) == 2 {
		switch os.Args[1] {
		case "generate":
			return generateIdentity()
		case "probe":
			return connect(true)
		}
	}
	if len(os.Args) != 1 {
		return errors.New("unexpected SSH helper arguments")
	}
	// Android may kill restic while a blocked network write keeps its child alive.
	parent := os.Getppid()
	if parent == 1 {
		return errors.New("restic exited")
	}
	go func() {
		for range time.Tick(time.Second) {
			if os.Getppid() != parent {
				os.Exit(1)
			}
		}
	}()
	return connect(false)
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
