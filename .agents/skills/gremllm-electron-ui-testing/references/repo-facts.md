# Gremllm Electron UI Testing Repo Facts

- Local app process name for UI automation: `Electron`
- Repo root is the working directory for build and direct-launch commands
- Preferred local validation launch path: `npm run build` then `./node_modules/.bin/electron . --remote-debugging-port=9222`
- Default CDP port: `9222`
- Primary renderer target title: `Gremllm`
- Disposable fixture outputs must live under `/tmp/gremllm-*`
- Document DOM anchor for validation: `.document-panel article`
- Workspace picker dialog title: `Open Workspace Folder`
- File-menu accelerator for opening a workspace: `Cmd+O`
- Canonical sample markdown fixture already in repo: `resources/gremllm-launch-log.md`
- Build and test commands from `package.json`: `npm run build`, `npm run test:ci`
