(ns gremllm.main.effects.acp.claude-adapter
  "Claude-adapter overrides for ACP session setup.

   These knobs are keyed to the pinned claude-agent-acp session setup path
   (local package node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137).
   Adapter reads params._meta.claudeCode.options, merges env, and defaults settingSources
   in that path. It then hardcodes executable: process.execPath for the non-static-binary
   branch, so _meta.claudeCode.options.executable is not a working override in this repo's
   pinned adapter version.

   Gremllm therefore injects:
   - ELECTRON_RUN_AS_NODE=1 so the packaged Electron binary (process.execPath) acts as a
     Node interpreter instead of relaunching the app window. This depends on
     FuseV1Options.RunAsNode remaining enabled; see https://packages.electronjs.org/fuses
   - settingSources: [] to suppress Claude Code SDK user/project/local settings loading for
     Gremllm sessions. The adapter's own SettingsManager lifecycle remains separate.")

(def session-meta
  #js {:claudeCode
       #js {:options
            #js {:env            #js {:ELECTRON_RUN_AS_NODE "1"}
                 :settingSources #js []}}})
