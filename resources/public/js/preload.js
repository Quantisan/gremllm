const { contextBridge, ipcRenderer } = require("electron/renderer");

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
			const requestId = crypto.randomUUID();
			
			// Set up one-time listeners for this request
			const successChannel = `${channel}-success-${requestId}`;
			const errorChannel = `${channel}-error-${requestId}`;
			
			ipcRenderer.once(successChannel, (event, result) => {
				resolve(result);
			});
			
			ipcRenderer.once(errorChannel, (event, error) => {
				reject(new Error(error));
			});
			
			// Send request with ID so main process knows where to reply
			ipcRenderer.send(channel, requestId, ...args);
		});
	};
};

contextBridge.exposeInMainWorld("electronAPI", {
	sendMessage: createIPCBoundary("chat/send-message"),
	saveTopic: (topicData) => ipcRenderer.invoke("topic/save", topicData),
	loadTopic: () => ipcRenderer.invoke("topic/load"),
	onMenuCommand: (command, callback) => ipcRenderer.on(command, callback),
	onSystemInfo: (callback) => {
		console.log("[PRELOAD] Setting up system:info listener");
		ipcRenderer.on("system:info", (event, systemInfo) => {
			console.log("[PRELOAD] Received system:info:", systemInfo);
			callback(systemInfo);
		});
	},
	// Secrets API
	saveSecret: (key, value) => ipcRenderer.invoke("secrets/save", key, value),
	loadSecret: (key) => ipcRenderer.invoke("secrets/load", key),
	deleteSecret: (key) => ipcRenderer.invoke("secrets/delete", key),
	listSecretKeys: () => ipcRenderer.invoke("secrets/list-keys"),
	checkSecretsAvailability: () => ipcRenderer.invoke("secrets/check-availability"),
});
