# SELEXTrace Launcher

A Swing desktop launcher for orchestrating the SELEXTrace platform locally.

## Included

- FlatLaf light/dark theme toggle
- 4-step wizard
- Prerequisite checks
- GitHub release artifact download and cache
- Local configuration stored in `selextrace.cfg`
- PostgreSQL, backend, and frontend process orchestration
- System tray integration and close-to-tray behavior

## Notes

The launcher expects:
- Java SDK 21+ for the launcher build; the wizard still enforces Java 25+ when checking the host for backend support.
- Docker available locally, or Docker via WSL on Windows
- `npx`, `python3`, or `python` for local static file hosting
