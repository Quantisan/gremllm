const { FusesPlugin } = require("@electron-forge/plugin-fuses");
const { FuseV1Options, FuseVersion } = require("@electron/fuses");

module.exports = {
	name: "Gremllm",
	packagerConfig: {
		asar: true,
	},
	rebuildConfig: {},
	makers: [
		{
			name: "@electron-forge/maker-dmg",
			config: {
				format: "ULFO",
			},
			platforms: ["darwin"],
		},
	],
	publishers: [
		{
			name: "@electron-forge/publisher-github",
			config: {
				repository: {
					owner: "Quantisan",
					name: "gremllm",
				},
				prerelease: true,
			},
		},
	],
	plugins: [
		{
			name: "@electron-forge/plugin-auto-unpack-natives",
			config: {},
		},
		// Fuses are used to enable/disable various Electron functionality
		// at package time, before code signing the application
		new FusesPlugin({
			version: FuseVersion.V1,
			// Electron's fuse docs make RunAsNode the switch that disables or enables
			// ELECTRON_RUN_AS_NODE. Gremllm keeps it on because the pinned
			// claude-agent-acp session setup hardcodes executable: process.execPath
			// (local package node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1131-1133),
			// and packaged launch only works when session-meta also injects
			// ELECTRON_RUN_AS_NODE=1. Trade-off: enabling this fuse makes that env var
			// meaningful again for the app binary. Source: https://packages.electronjs.org/fuses
			[FuseV1Options.RunAsNode]: true,
			[FuseV1Options.EnableCookieEncryption]: true,
			[FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
			[FuseV1Options.EnableNodeCliInspectArguments]: false,
			[FuseV1Options.EnableEmbeddedAsarIntegrityValidation]: true,
			[FuseV1Options.OnlyLoadAppFromAsar]: true,
		}),
	],
};
