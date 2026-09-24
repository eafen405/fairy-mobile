# Fairybot public-wire compatibility fixture

Generated on September 24, 2026 from Fairybot commit
`d6e727fd228dd1aa4e8369e527a8e71998c788d6`, with no Fairybot source edits.
The mobile decoder baseline is `19ef936d`.

## Generation and checks

The generation command, run from `/home/eafen405/projects/Fairybot`, was:

```sh
node --import tsx /tmp/client-boundary-http-fixture.mjs
```

The temporary generator follows `tests/mobile.spec.ts`'s public HTTP fixture pattern:

1. Create a disposable data root; call `bootFairyMinimal(dataRoot, { webPort: 0 })`
   and `attachWebEntry` with the repository's `web` directory and `dev: true`.
2. Capture the bootstrap invitation and register `master` through `POST /api/register`.
   Carry the returned `fairy_login` cookie through subsequent requests.
3. Obtain the main mobile session ID from `GET /api/mobile/v1/sessions`, then resolve
   the persisted session ID with `sessionIdOfMobileId`.
4. Pass the JSON fixture's `events` to `tests/persisted.ts`'s `writePersistedSession`
   in that user's workspace. Supply `surface: { surfaceOp: 'append' }` for
   `user/message`, `assistant/message`, and `tool/result`; the user message also
   supplies `sourceEventSeqs: [0]`. Fix `Date.now()` to `1800000000000` only during
   this persistence step, restoring it immediately afterward. The helper assigns
   sequential timestamps, so paired calls/results have a genuine fixture duration of 1 ms.
5. Request `GET /api/mobile/v1/sessions/:id?includeActivity=true&includeMetadata=true`
   using the cookie; require HTTP 200 and capture its JSON body as `page`, including
   nodes, revisions and runtime. The exact generated route is in `provenance`.
6. Read the persisted events back with `persistedEvents` and retain them as `events`.
   Verify `projectHistoryEvents(events).messages` equals the HTTP page's messages.
   Independently call `SessionProjection.create` with those events, a fresh
   `SessionEventBus` and idle state, and verify its entire `page()` equals the HTTP body.
7. Assert the HTTP response contains none of `PRIVATE_`, `/host/private`,
   `memory_search`, `open_thread`, or the temporary data-root path. Dispose the
   projection, web entry and context and remove the temporary data root.

`events` is generator input and persisted-record evidence, not client wire data.
It deliberately contains `PRIVATE_TOOL`, `PRIVATE_ARGUMENT`, `PRIVATE_RESULT`,
`PRIVATE_EXCEPTION`, `PRIVATE_REASONING`, and `/host/private`. The compatibility test
decodes only `page` and checks marker exclusion there. The page is the parsed public
HTTP response without field rewriting; formatting and provenance are the fixture wrapper.

## Decoder fidelity and evidence boundary

The independent `Legacy*` DTOs in `FiloClientTest.kt` preserve serialized fields,
types, required/null/default semantics from baseline `RemoteProtocol.kt` and
`RemoteTopology.kt`. They omit only `@Transient` presentation/cache fields.
`legacyValidate` preserves the baseline `FiloClient.decodePage` validation predicates,
including the required nonblank `toolName` for tool records. Runtime, queue and node
models are independent mirrors rather than current production DTOs.

The public page covers normal user/assistant text, a successful mapped activity,
a failed unknown activity with a safe note, an interrupted activity marked stopped,
and reasoning omitted from the wire. The legacy decoder accepts safe label aliases;
the current decoder consumes labels and notes. Both message bodies and node metadata
are present. This fixture verifies persisted-history public HTTP and decoder compatibility;
live model/device qualification and end-to-end hydration/rendering use separate evidence.
