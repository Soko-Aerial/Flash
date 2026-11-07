# Core Enterprise Module (`:core:enterprise`)

The experimental Hub client handles enrolment, delegated credentials, authentication,
directory sync, relay connections, sealing keys and revocation updates. It is published
as `core-enterprise` and is on the compile classpath for `core-messaging` consumers
because its directory and join DTOs appear in messaging signatures.

Start with the host's `FlashChatRepository` for Hub-backed people, departments, groups,
join approvals and announcements. The lower-level clients and DTOs marked
`@FlashInternalApi` may change between builds. Use explicit opt-in when working on
those integration seams.

See the [Hub protocol module](core-hub-protocol.md) for the shared wire types and the
[operator guide](../../../deploy/HUB-SERVER-DEPLOYMENT.md) for deploying a test Hub.
