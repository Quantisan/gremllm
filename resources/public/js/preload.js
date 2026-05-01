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

/**
 * @returns {Promise<string>} New ACP session ID.
 */
const acpNewSession = createIPCBoundary("acp/new-session");

/**
 * @param {string} sessionId Stored ACP session ID.
 * @returns {Promise<string>} Resumed ACP session ID.
 */
const acpResumeSession = createIPCBoundary("acp/resume-session");

/**
 * @param {string} sessionId Topic ACP session ID.
 * @param {object} message Structured user message.
 * @returns {Promise<{stopReason: string}>} ACP prompt result.
 */
const acpPrompt = createIPCBoundary("acp/prompt");

contextBridge.exposeInMainWorld("electronAPI", {
	saveTopic: (topicData) => ipcRenderer.invoke("topic/save", topicData),
	deleteTopic: (topicId) => ipcRenderer.invoke("topic/delete", topicId),
	createDocument: () => ipcRenderer.invoke("document/create"),
	pickWorkspaceFolder: () => ipcRenderer.invoke("workspace/pick-folder"),
	reloadWorkspace: () => ipcRenderer.invoke("workspace/reload"),
	onMenuCommand: (callback) => ipcRenderer.on("menu:command", callback),
	onWorkspaceOpened: (callback) => ipcRenderer.on("workspace:opened", callback),
	onAcpSessionUpdate: (callback) => ipcRenderer.on("acp:session-update", callback),
	// File path API - uses webUtils.getPathForFile to get filesystem paths from File objects
	getFilePath: (file) => webUtils.getPathForFile(file),
	// ACP API
	acpNewSession,
	acpResumeSession,
	acpPrompt,
});
