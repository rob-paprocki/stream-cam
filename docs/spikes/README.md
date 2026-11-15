# Spike notes

A spike is a small, throwaway app or script that answers one question before the app is built on the
answer. Can this library open that capture card? Does this socket stay on cellular while Wi-Fi is up?
Each spike has an ID (S1, S2 and so on) and gets one short note in this folder, named
`S<number>-<topic>.md`, for example `S3-srt-listener.md`.

A note gives the answer, the evidence for it, and any change the spec needs. It stays short: someone
deciding whether to build on the answer should get it in a minute.

## Rules

- Cite requirement IDs from the spec, such as SRC-5 or NET-6, for everything the spike affects.
- Use example values only: IPv4 addresses from 192.168.100.0/24 (and the documentation ranges
  192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24), IPv6 from 2001:db8::/32, host names under
  example.com, and MAC addresses 00:00:5E:00:53:xx. Rewrite any log excerpt to those values before it
  goes in.
- Name the hardware the spike ran on: the phone's make, model and Android version, and the capture
  card's make and model. Leave out serial numbers and any other ID that belongs to one device.
- Raw evidence stays out of these notes. Full logs, packet captures, frame dumps and real addresses are
  kept privately, and the note only says what kinds were kept.

## Template

```markdown
# S<number>: <the question, in a few words>

Date: YYYY-MM-DD. Requirements: <IDs, such as SRC-5, NET-6>.

## Question

<The spike's question, as the spike list asks it.>

## Answer

<A short paragraph that starts with yes, no, or "yes, but".>

## Evidence

<What ran, on which phone and capture card (make and model), and what it showed. Give numbers, short
log excerpts rewritten to example values, and the commands someone could run to repeat it.>

## Spec changes

<Each change, with the requirement ID it touches. Or "None".>

## Raw evidence

Kept privately: <the kinds of evidence, for example full logs and a packet capture>.
```
