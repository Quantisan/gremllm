Title: Inter-Process Communication | Electron

URL Source: https://www.electronjs.org/docs/latest/tutorial/ipc

Published Time: Wed, 09 Jul 2025 16:12:31 GMT

Markdown Content:
Inter-process communication (IPC) is a key part of building feature-rich desktop applications in Electron. Because the main and renderer processes have different responsibilities in Electron's process model, IPC is the only way to perform many common tasks, such as calling a native API from your UI or triggering changes in your web contents from native menus.

IPC channels[​](https://www.electronjs.org/docs/latest/tutorial/ipc#ipc-channels "Direct link to IPC channels")
---------------------------------------------------------------------------------------------------------------

In Electron, processes communicate by passing messages through developer-defined "channels" with the [`ipcMain`](https://www.electronjs.org/docs/latest/api/ipc-main) and [`ipcRenderer`](https://www.electronjs.org/docs/latest/api/ipc-renderer) modules. These channels are **arbitrary** (you can name them anything you want) and **bidirectional** (you can use the same channel name for both modules).

In this guide, we'll be going over some fundamental IPC patterns with concrete examples that you can use as a reference for your app code.

Understanding context-isolated processes[​](https://www.electronjs.org/docs/latest/tutorial/ipc#understanding-context-isolated-processes "Direct link to Understanding context-isolated processes")
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

Before proceeding to implementation details, you should be familiar with the idea of using a [preload script](https://www.electronjs.org/docs/latest/tutorial/process-model#preload-scripts) to import Node.js and Electron modules in a context-isolated renderer process.

*   For a full overview of Electron's process model, you can read the [process model docs](https://www.electronjs.org/docs/latest/tutorial/process-model).
*   For a primer into exposing APIs from your preload script using the `contextBridge` module, check out the [context isolation tutorial](https://www.electronjs.org/docs/latest/tutorial/context-isolation).

Pattern 1: Renderer to main (one-way)[​](https://www.electronjs.org/docs/latest/tutorial/ipc#pattern-1-renderer-to-main-one-way "Direct link to Pattern 1: Renderer to main (one-way)")
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

To fire a one-way IPC message from a renderer process to the main process, you can use the [`ipcRenderer.send`](https://www.electronjs.org/docs/latest/api/ipc-renderer) API to send a message that is then received by the [`ipcMain.on`](https://www.electronjs.org/docs/latest/api/ipc-main) API.

You usually use this pattern to call a main process API from your web contents. We'll demonstrate this pattern by creating a simple app that can programmatically change its window title.

For this demo, you'll need to add code to your main process, your renderer process, and a preload script. The full code is below, but we'll be explaining each file individually in the following sections.

*   main.js
*   preload.js
*   index.html
*   renderer.js

`const { app, BrowserWindow, ipcMain } = require('electron/main')const path = require('node:path')function handleSetTitle (event, title) {  const webContents = event.sender  const win = BrowserWindow.fromWebContents(webContents)  win.setTitle(title)}function createWindow () {  const mainWindow = new BrowserWindow({    webPreferences: {      preload: path.join(__dirname, 'preload.js')    }  })  mainWindow.loadFile('index.html')}app.whenReady().then(() => {  ipcMain.on('set-title', handleSetTitle)  createWindow()  app.on('activate', function () {    if (BrowserWindow.getAllWindows().length === 0) createWindow()  })})app.on('window-all-closed', function () {  if (process.platform !== 'darwin') app.quit()})`

### 1. Listen for events with `ipcMain.on`[​](https://www.electronjs.org/docs/latest/tutorial/ipc#1-listen-for-events-with-ipcmainon "Direct link to 1-listen-for-events-with-ipcmainon")

In the main process, set an IPC listener on the `set-title` channel with the `ipcMain.on` API:

main.js (Main Process)

`const { app, BrowserWindow, ipcMain } = require('electron')const path = require('node:path')// ...function handleSetTitle (event, title) {  const webContents = event.sender  const win = BrowserWindow.fromWebContents(webContents)  win.setTitle(title)}function createWindow () {  const mainWindow = new BrowserWindow({    webPreferences: {      preload: path.join(__dirname, 'preload.js')    }  })  mainWindow.loadFile('index.html')}app.whenReady().then(() => {  ipcMain.on('set-title', handleSetTitle)  createWindow()})// ...`

The above `handleSetTitle` callback has two parameters: an [IpcMainEvent](https://www.electronjs.org/docs/latest/api/structures/ipc-main-event) structure and a `title` string. Whenever a message comes through the `set-title` channel, this function will find the BrowserWindow instance attached to the message sender and use the `win.setTitle` API on it.

info

Make sure you're loading the `index.html` and `preload.js` entry points for the following steps!

### 2. Expose `ipcRenderer.send` via preload[​](https://www.electronjs.org/docs/latest/tutorial/ipc#2-expose-ipcrenderersend-via-preload "Direct link to 2-expose-ipcrenderersend-via-preload")

To send messages to the listener created above, you can use the `ipcRenderer.send` API. By default, the renderer process has no Node.js or Electron module access. As an app developer, you need to choose which APIs to expose from your preload script using the `contextBridge` API.

In your preload script, add the following code, which will expose a global `window.electronAPI` variable to your renderer process.

preload.js (Preload Script)

`const { contextBridge, ipcRenderer } = require('electron')contextBridge.exposeInMainWorld('electronAPI', {  setTitle: (title) => ipcRenderer.send('set-title', title)})`

At this point, you'll be able to use the `window.electronAPI.setTitle()` function in the renderer process.

Security warning

We don't directly expose the whole `ipcRenderer.send` API for [security reasons](https://www.electronjs.org/docs/latest/tutorial/context-isolation#security-considerations). Make sure to limit the renderer's access to Electron APIs as much as possible.

### 3. Build the renderer process UI[​](https://www.electronjs.org/docs/latest/tutorial/ipc#3-build-the-renderer-process-ui "Direct link to 3. Build the renderer process UI")

In our BrowserWindow's loaded HTML file, add a basic user interface consisting of a text input and a button:

index.html

`<!DOCTYPE html><html>  <head>    <meta charset="UTF-8">    <!-- https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP -->    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'">    <title>Hello World!</title>  </head>  <body>    Title: <input id="title"/>    <button id="btn" type="button">Set</button>    <script src="./renderer.js"></script>  </body></html>`

To make these elements interactive, we'll be adding a few lines of code in the imported `renderer.js` file that leverages the `window.electronAPI` functionality exposed from the preload script:

renderer.js (Renderer Process)

`const setButton = document.getElementById('btn')const titleInput = document.getElementById('title')setButton.addEventListener('click', () => {  const title = titleInput.value  window.electronAPI.setTitle(title)})`

At this point, your demo should be fully functional. Try using the input field and see what happens to your BrowserWindow title!

Pattern 2: Renderer to main (two-way)[​](https://www.electronjs.org/docs/latest/tutorial/ipc#pattern-2-renderer-to-main-two-way "Direct link to Pattern 2: Renderer to main (two-way)")
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

A common application for two-way IPC is calling a main process module from your renderer process code and waiting for a result. This can be done by using [`ipcRenderer.invoke`](https://www.electronjs.org/docs/latest/api/ipc-renderer#ipcrendererinvokechannel-args) paired with [`ipcMain.handle`](https://www.electronjs.org/docs/latest/api/ipc-main#ipcmainhandlechannel-listener).

In the following example, we'll be opening a native file dialog from the renderer process and returning the selected file's path.

For this demo, you'll need to add code to your main process, your renderer process, and a preload script. The full code is below, but we'll be explaining each file individually in the following sections.

*   main.js
*   preload.js
*   index.html
*   renderer.js

`const { app, BrowserWindow, ipcMain, dialog } = require('electron/main')const path = require('node:path')async function handleFileOpen () {  const { canceled, filePaths } = await dialog.showOpenDialog()  if (!canceled) {    return filePaths[0]  }}function createWindow () {  const mainWindow = new BrowserWindow({    webPreferences: {      preload: path.join(__dirname, 'preload.js')    }  })  mainWindow.loadFile('index.html')}app.whenReady().then(() => {  ipcMain.handle('dialog:openFile', handleFileOpen)  createWindow()  app.on('activate', function () {    if (BrowserWindow.getAllWindows().length === 0) createWindow()  })})app.on('window-all-closed', function () {  if (process.platform !== 'darwin') app.quit()})`

### 1. Listen for events with `ipcMain.handle`[​](https://www.electronjs.org/docs/latest/tutorial/ipc#1-listen-for-events-with-ipcmainhandle "Direct link to 1-listen-for-events-with-ipcmainhandle")

In the main process, we'll be creating a `handleFileOpen()` function that calls `dialog.showOpenDialog` and returns the value of the file path selected by the user. This function is used as a callback whenever an `ipcRender.invoke` message is sent through the `dialog:openFile` channel from the renderer process. The return value is then returned as a Promise to the original `invoke` call.

A word on error handling

Errors thrown through `handle` in the main process are not transparent as they are serialized and only the `message` property from the original error is provided to the renderer process. Please refer to [#24427](https://github.com/electron/electron/issues/24427) for details.

main.js (Main Process)

`const { app, BrowserWindow, dialog, ipcMain } = require('electron')const path = require('node:path')// ...async function handleFileOpen () {  const { canceled, filePaths } = await dialog.showOpenDialog({})  if (!canceled) {    return filePaths[0]  }}function createWindow () {  const mainWindow = new BrowserWindow({    webPreferences: {      preload: path.join(__dirname, 'preload.js')    }  })  mainWindow.loadFile('index.html')}app.whenReady().then(() => {  ipcMain.handle('dialog:openFile', handleFileOpen)  createWindow()})// ...`

on channel names

The `dialog:` prefix on the IPC channel name has no effect on the code. It only serves as a namespace that helps with code readability.

info

Make sure you're loading the `index.html` and `preload.js` entry points for the following steps!

### 2. Expose `ipcRenderer.invoke` via preload[​](https://www.electronjs.org/docs/latest/tutorial/ipc#2-expose-ipcrendererinvoke-via-preload "Direct link to 2-expose-ipcrendererinvoke-via-preload")

In the preload script, we expose a one-line `openFile` function that calls and returns the value of `ipcRenderer.invoke('dialog:openFile')`. We'll be using this API in the next step to call the native dialog from our renderer's user interface.

preload.js (Preload Script)

`const { contextBridge, ipcRenderer } = require('electron')contextBridge.exposeInMainWorld('electronAPI', {  openFile: () => ipcRenderer.invoke('dialog:openFile')})`

Security warning

We don't directly expose the whole `ipcRenderer.invoke` API for [security reasons](https://www.electronjs.org/docs/latest/tutorial/context-isolation#security-considerations). Make sure to limit the renderer's access to Electron APIs as much as possible.

### 3. Build the renderer process UI[​](https://www.electronjs.org/docs/latest/tutorial/ipc#3-build-the-renderer-process-ui-1 "Direct link to 3. Build the renderer process UI")

Finally, let's build the HTML file that we load into our BrowserWindow.

index.html

`<!DOCTYPE html><html>  <head>    <meta charset="UTF-8">    <!-- https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP -->    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'">    <title>Dialog</title>  </head>  <body>    <button type="button" id="btn">Open a File</button>    File path: <strong id="filePath"></strong>    <script src='./renderer.js'></script>  </body></html>`

The UI consists of a single `#btn` button element that will be used to trigger our preload API, and a `#filePath` element that will be used to display the path of the selected file. Making these pieces work will take a few lines of code in the renderer process script:

renderer.js (Renderer Process)

`const btn = document.getElementById('btn')const filePathElement = document.getElementById('filePath')btn.addEventListener('click', async () => {  const filePath = await window.electronAPI.openFile()  filePathElement.innerText = filePath})`

In the above snippet, we listen for clicks on the `#btn` button, and call our `window.electronAPI.openFile()` API to activate the native Open File dialog. We then display the selected file path in the `#filePath` element.

### Note: legacy approaches[​](https://www.electronjs.org/docs/latest/tutorial/ipc#note-legacy-approaches "Direct link to Note: legacy approaches")

The `ipcRenderer.invoke` API was added in Electron 7 as a developer-friendly way to tackle two-way IPC from the renderer process. However, a couple of alternative approaches to this IPC pattern exist.

Avoid legacy approaches if possible

We recommend using `ipcRenderer.invoke` whenever possible. The following two-way renderer-to-main patterns are documented for historical purposes.

info

For the following examples, we're calling `ipcRenderer` directly from the preload script to keep the code samples small.

#### Using `ipcRenderer.send`[​](https://www.electronjs.org/docs/latest/tutorial/ipc#using-ipcrenderersend "Direct link to using-ipcrenderersend")

The `ipcRenderer.send` API that we used for single-way communication can also be leveraged to perform two-way communication. This was the recommended way for asynchronous two-way communication via IPC prior to Electron 7.

preload.js (Preload Script)

`// You can also put expose this code to the renderer// process with the `contextBridge` APIconst { ipcRenderer } = require('electron')ipcRenderer.on('asynchronous-reply', (_event, arg) => {  console.log(arg) // prints "pong" in the DevTools console})ipcRenderer.send('asynchronous-message', 'ping')`

main.js (Main Process)

`ipcMain.on('asynchronous-message', (event, arg) => {  console.log(arg) // prints "ping" in the Node console  // works like `send`, but returning a message back  // to the renderer that sent the original message  event.reply('asynchronous-reply', 'pong')})`

There are a couple downsides to this approach:

*   You need to set up a second `ipcRenderer.on` listener to handle the response in the renderer process. With `invoke`, you get the response value returned as a Promise to the original API call.
*   There's no obvious way to pair the `asynchronous-reply` message to the original `asynchronous-message` one. If you have very frequent messages going back and forth through these channels, you would need to add additional app code to track each call and response individually.

#### Using `ipcRenderer.sendSync`[​](https://www.electronjs.org/docs/latest/tutorial/ipc#using-ipcrenderersendsync "Direct link to using-ipcrenderersendsync")

The `ipcRenderer.sendSync` API sends a message to the main process and waits _synchronously_ for a response.

main.js (Main Process)

`const { ipcMain } = require('electron')ipcMain.on('synchronous-message', (event, arg) => {  console.log(arg) // prints "ping" in the Node console  event.returnValue = 'pong'})`

preload.js (Preload Script)

`// You can also put expose this code to the renderer// process with the `contextBridge` APIconst { ipcRenderer } = require('electron')const result = ipcRenderer.sendSync('synchronous-message', 'ping')console.log(result) // prints "pong" in the DevTools console`

The structure of this code is very similar to the `invoke` model, but we recommend **avoiding this API** for performance reasons. Its synchronous nature means that it'll block the renderer process until a reply is received.

Pattern 3: Main to renderer[​](https://www.electronjs.org/docs/latest/tutorial/ipc#pattern-3-main-to-renderer "Direct link to Pattern 3: Main to renderer")
-----------------------------------------------------------------------------------------------------------------------------------------------------------

When sending a message from the main process to a renderer process, you need to specify which renderer is receiving the message. Messages need to be sent to a renderer process via its [`WebContents`](https://www.electronjs.org/docs/latest/api/web-contents) instance. This WebContents instance contains a [`send`](https://www.electronjs.org/docs/latest/api/web-contents#contentssendchannel-args) method that can be used in the same way as `ipcRenderer.send`.

To demonstrate this pattern, we'll be building a number counter controlled by the native operating system menu.

For this demo, you'll need to add code to your main process, your renderer process, and a preload script. The full code is below, but we'll be explaining each file individually in the following sections.

*   main.js
*   preload.js
*   index.html
*   renderer.js

`const { app, BrowserWindow, Menu, ipcMain } = require('electron/main')const path = require('node:path')function createWindow () {  const mainWindow = new BrowserWindow({    webPreferences: {      preload: path.join(__dirname, 'preload.js')    }  })  const menu = Menu.buildFromTemplate([    {      label: app.name,      submenu: [        {          click: () => mainWindow.webContents.send('update-counter', 1),          label: 'Increment'        },        {          click: () => mainWindow.webContents.send('update-counter', -1),          label: 'Decrement'        }      ]    }  ])  Menu.setApplicationMenu(menu)  mainWindow.loadFile('index.html')  // Open the DevTools.  mainWindow.webContents.openDevTools()}app.whenReady().then(() => {  ipcMain.on('counter-value', (_event, value) => {    console.log(value) // will print value to Node console  })  createWindow()  app.on('activate', function () {    if (BrowserWindow.getAllWindows().length === 0) createWindow()  })})app.on('window-all-closed', function () {  if (process.platform !== 'darwin') app.quit()})`

### 1. Send messages with the `webContents` module[​](https://www.electronjs.org/docs/latest/tutorial/ipc#1-send-messages-with-the-webcontents-module "Direct link to 1-send-messages-with-the-webcontents-module")

For this demo, we'll need to first build a custom menu in the main process using Electron's `Menu` module that uses the `webContents.send` API to send an IPC message from the main process to the target renderer.

main.js (Main Process)

`const { app, BrowserWindow, Menu, ipcMain } = require('electron')const path = require('node:path')function createWindow () {  const mainWindow = new BrowserWindow({    webPreferences: {      preload: path.join(__dirname, 'preload.js')    }  })  const menu = Menu.buildFromTemplate([    {      label: app.name,      submenu: [        {          click: () => mainWindow.webContents.send('update-counter', 1),          label: 'Increment'        },        {          click: () => mainWindow.webContents.send('update-counter', -1),          label: 'Decrement'        }      ]    }  ])  Menu.setApplicationMenu(menu)  mainWindow.loadFile('index.html')}// ...`

For the purposes of the tutorial, it's important to note that the `click` handler sends a message (either `1` or `-1`) to the renderer process through the `update-counter` channel.

`click: () => mainWindow.webContents.send('update-counter', -1)`

info

Make sure you're loading the `index.html` and `preload.js` entry points for the following steps!

### 2. Expose `ipcRenderer.on` via preload[​](https://www.electronjs.org/docs/latest/tutorial/ipc#2-expose-ipcrendereron-via-preload "Direct link to 2-expose-ipcrendereron-via-preload")

Like in the previous renderer-to-main example, we use the `contextBridge` and `ipcRenderer` modules in the preload script to expose IPC functionality to the renderer process:

preload.js (Preload Script)

`const { contextBridge, ipcRenderer } = require('electron')contextBridge.exposeInMainWorld('electronAPI', {  onUpdateCounter: (callback) => ipcRenderer.on('update-counter', (_event, value) => callback(value))})`

After loading the preload script, your renderer process should have access to the `window.electronAPI.onUpdateCounter()` listener function.

Security warning

We don't directly expose the whole `ipcRenderer.on` API for [security reasons](https://www.electronjs.org/docs/latest/tutorial/context-isolation#security-considerations). Make sure to limit the renderer's access to Electron APIs as much as possible. Also don't just pass the callback to `ipcRenderer.on` as this will leak `ipcRenderer` via `event.sender`. Use a custom handler that invoke the `callback` only with the desired arguments.

info

In the case of this minimal example, you can call `ipcRenderer.on` directly in the preload script rather than exposing it over the context bridge.

preload.js (Preload Script)

`const { ipcRenderer } = require('electron')window.addEventListener('DOMContentLoaded', () => {  const counter = document.getElementById('counter')  ipcRenderer.on('update-counter', (_event, value) => {    const oldValue = Number(counter.innerText)    const newValue = oldValue + value    counter.innerText = newValue  })})`

However, this approach has limited flexibility compared to exposing your preload APIs over the context bridge, since your listener can't directly interact with your renderer code.

### 3. Build the renderer process UI[​](https://www.electronjs.org/docs/latest/tutorial/ipc#3-build-the-renderer-process-ui-2 "Direct link to 3. Build the renderer process UI")

To tie it all together, we'll create an interface in the loaded HTML file that contains a `#counter` element that we'll use to display the values:

index.html

`<!DOCTYPE html><html>  <head>    <meta charset="UTF-8">    <!-- https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP -->    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'">    <title>Menu Counter</title>  </head>  <body>    Current value: <strong id="counter">0</strong>    <script src="./renderer.js"></script>  </body></html>`

Finally, to make the values update in the HTML document, we'll add a few lines of DOM manipulation so that the value of the `#counter` element is updated whenever we fire an `update-counter` event.

renderer.js (Renderer Process)

`const counter = document.getElementById('counter')window.electronAPI.onUpdateCounter((value) => {  const oldValue = Number(counter.innerText)  const newValue = oldValue + value  counter.innerText = newValue.toString()})`

In the above code, we're passing in a callback to the `window.electronAPI.onUpdateCounter` function exposed from our preload script. The second `value` parameter corresponds to the `1` or `-1` we were passing in from the `webContents.send` call from the native menu.

### Optional: returning a reply[​](https://www.electronjs.org/docs/latest/tutorial/ipc#optional-returning-a-reply "Direct link to Optional: returning a reply")

There's no equivalent for `ipcRenderer.invoke` for main-to-renderer IPC. Instead, you can send a reply back to the main process from within the `ipcRenderer.on` callback.

We can demonstrate this with slight modifications to the code from the previous example. In the renderer process, expose another API to send a reply back to the main process through the `counter-value` channel.

preload.js (Preload Script)

`const { contextBridge, ipcRenderer } = require('electron')contextBridge.exposeInMainWorld('electronAPI', {  onUpdateCounter: (callback) => ipcRenderer.on('update-counter', (_event, value) => callback(value)),  counterValue: (value) => ipcRenderer.send('counter-value', value)})`

renderer.js (Renderer Process)

`const counter = document.getElementById('counter')window.electronAPI.onUpdateCounter((value) => {  const oldValue = Number(counter.innerText)  const newValue = oldValue + value  counter.innerText = newValue.toString()  window.electronAPI.counterValue(newValue)})`

In the main process, listen for `counter-value` events and handle them appropriately.

main.js (Main Process)

`// ...ipcMain.on('counter-value', (_event, value) => {  console.log(value) // will print value to Node console})// ...`

Pattern 4: Renderer to renderer[​](https://www.electronjs.org/docs/latest/tutorial/ipc#pattern-4-renderer-to-renderer "Direct link to Pattern 4: Renderer to renderer")
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------

There's no direct way to send messages between renderer processes in Electron using the `ipcMain` and `ipcRenderer` modules. To achieve this, you have two options:

*   Use the main process as a message broker between renderers. This would involve sending a message from one renderer to the main process, which would forward the message to the other renderer.
*   Pass a [MessagePort](https://www.electronjs.org/docs/latest/tutorial/message-ports) from the main process to both renderers. This will allow direct communication between renderers after the initial setup.

Object serialization[​](https://www.electronjs.org/docs/latest/tutorial/ipc#object-serialization "Direct link to Object serialization")
---------------------------------------------------------------------------------------------------------------------------------------

Electron's IPC implementation uses the HTML standard [Structured Clone Algorithm](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm) to serialize objects passed between processes, meaning that only certain types of objects can be passed through IPC channels.

In particular, DOM objects (e.g. `Element`, `Location` and `DOMMatrix`), Node.js objects backed by C++ classes (e.g. `process.env`, some members of `Stream`), and Electron objects backed by C++ classes (e.g. `WebContents`, `BrowserWindow` and `WebFrame`) are not serializable with Structured Clone.
