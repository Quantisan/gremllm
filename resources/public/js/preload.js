const { contextBridge, ipcRenderer, webUtils } = require("electron/renderer");

/**
 * Creates a promise-based wrapper for Electron's event-based IPC.
 *
 * Problem: The renderer needs to know when async operations in the main
 * process complete (e.g., API calls, file saves), but Electron IPC is
 * fire-and-forget between processes.
 *
 * Solution: Use unique IDs to create temporary request-response channels.
 *
 * This is boundary code - it creates the illusion of RPC (remote procedure
 * calls) over Electron's event-based IPC.
 */
const createIPCBoundary = (channel) => {
	return (...args) => {
		return new Promise((resolve, reject) => {
			// Use standard UUID for request-response matching
			const ipcCorrelationId = crypto.randomUUID();

			// Set up one-time listeners for this request
			const successChannel = `${channel}-success-${ipcCorrelationId}`;
			const errorChannel = `${channel}-error-${ipcCorrelationId}`;

			ipcRenderer.once(successChannel, (event, result) => {
				resolve(result);
			});

			ipcRenderer.once(errorChannel, (event, error) => {
				reject(new Error(error));
			});

			// Send request with ID so main process knows where to reply
			ipcRenderer.send(channel, ipcCorrelationId, ...args);
		});
	};
};

contextBridge.exposeInMainWorld("electronAPI", {
	sendMessage: createIPCBoundary("chat/send-message"),
	saveTopic: (topicData) => ipcRenderer.invoke("topic/save", topicData),
	deleteTopic: (topicId) => ipcRenderer.invoke("topic/delete", topicId),
	pickWorkspaceFolder: () => ipcRenderer.invoke("workspace/pick-folder"),
	reloadWorkspace: () => ipcRenderer.invoke("workspace/reload"),
	onMenuCommand: (command, callback) => ipcRenderer.on(command, callback),
	onWorkspaceOpened: (callback) => ipcRenderer.on("workspace:opened", callback),
	onAcpSessionUpdate: (callback) => ipcRenderer.on("acp:session-update", callback),
	getSystemInfo: () => ipcRenderer.invoke("system/get-info"),
	// Secrets API
	saveSecret: (key, value) => ipcRenderer.invoke("secrets/save", key, value),
	deleteSecret: (key) => ipcRenderer.invoke("secrets/delete", key),
	// File path API - uses webUtils.getPathForFile to get filesystem paths from File objects
	getFilePath: (file) => webUtils.getPathForFile(file),
	// ACP API
	acpNewSession: createIPCBoundary("acp/new-session"),
	acpResumeSession: createIPCBoundary("acp/resume-session"),
	acpPrompt: createIPCBoundary("acp/prompt"),
});
