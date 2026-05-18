# Open Any Markdown File Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple gremllm's per-document state from the user's document folder so users can open any `.md` file from anywhere on disk; gremllm's bookkeeping (topics, meta) lives under `<userData>/User/documents/<sha256(absolute-doc-path)>/`.

**Architecture:** Replace folder-picker with file-picker. Hash the document's absolute path to derive a private storage directory under Electron's `userData`. Rename IPC channels and bridge methods from `workspace/*` to `document/*`. Internal `workspace` naming is kept to minimize churn (a follow-up rename pass is out of scope). State is restructured: drop `:workspace-dir`, introduce `:active-document-path` and `:user-data-dir`; storage-dir and topics-dir become derived values. ACP `cwd` is `dirname(active-document-path)` — agent tool calls land next to the user's actual document, **not** in the EDN bookkeeping directory.

**Tech Stack:** ClojureScript (Shadow-CLJS), Electron, Nexus state management, Malli schema/codec, Node `crypto` (SHA-256), Node `path` (normalization).

---

## Spec Reference

This plan implements `docs/specs/2026-05-18.open-any-markdown-file.md`. Read it first.

## Critical Invariants (from advisor consultation)

1. **ACP cwd is `dirname(doc-path)`, NOT the storage dir.** The storage dir holds gremllm's EDN bookkeeping; the agent should operate next to the user's actual document. The spec's literal reading is wrong here.
2. **State shape (Option B):** Remove `:workspace-dir` from store entirely. Add `:active-document-path` (user's doc) and `:user-data-dir` (Electron `userData` path). Compute `storage-dir` and `topics-dir` as derived getters. Avoids the "one variable means two things" anti-pattern.
3. **Action key vs. file location:** Register the action key as `:document.actions/open` (per spec table), but keep the source file at `src/gremllm/main/actions/workspace.cljs` (per spec "internal `workspace` kept"). This is intentional and codified by the spec.
4. **Renderer `:workspace.actions/opened` keyword stays.** The IPC event renames (`workspace:opened` → `document:opened`) but the renderer's action keyword that handles the inbound payload is internal — no need to rename it in this change.
5. **userData init:** Electron's `app.getPath("userData")` is only available after `app.whenReady()`. Dispatch `[:app.actions/set-user-data-dir ...]` in `initialize-app` **before** `[:window.actions/create]` so storage paths can resolve.

---

## File Structure

**New / heavily modified:**
- `src/gremllm/main/io.cljs` — add `path->document-hash`, `document-storage-dir`; remove `document-file-path`.
- `src/gremllm/main/state.cljs` — remove `workspace-dir-path`; add `active-document-path`, `user-data-dir`, derived `get-storage-dir`, `get-topics-dir`, `get-active-document-path`.
- `src/gremllm/main/effects/workspace.cljs` — replace folder-dialog with file-dialog; rewrite `load-and-sync` to take a doc path; add `write-meta-if-missing!`; remove `create-document`.
- `src/gremllm/main/actions/workspace.cljs` — rename functions; register as `:document.actions/*` keys.
- `src/gremllm/main/actions.cljs` — `:acp.effects/send-prompt` uses `get-active-document-path`; ACP cwd derived from `dirname(doc-path)`; add `:app.actions/set-user-data-dir` registration.
- `src/gremllm/main/core.cljs` — IPC channel renames; drop `document/create`; dispatch `set-user-data-dir` before window creation.
- `src/gremllm/main/menu.cljs` — label change; dispatch keyword update.
- `resources/public/js/preload.js` — bridge method renames; channel-string renames; drop `createDocument`.
- `src/gremllm/renderer/core.cljs` — listen on renamed bridge method.
- `src/gremllm/renderer/actions.cljs` — drop `document.actions/create*` registrations; rename pick/reload.
- `src/gremllm/renderer/actions/workspace.cljs` — call renamed bridge methods.
- `src/gremllm/renderer/actions/document.cljs` — remove `create`/`create-success`/`create-error`.
- `src/gremllm/renderer/ui/document.cljs` — empty-state "Create Document" → "Open…".

**Deleted:**
- `src/gremllm/main/actions/document.cljs` — `create-plan` is the only function; unused after empty-state UI change.
- `test/gremllm/main/actions/document_test.cljs` — covers the deleted file.

**Test files touched:**
- `test/gremllm/main/io_test.cljs` — add hash + storage-dir tests.
- `test/gremllm/main/effects/workspace_test.cljs` — rewrite load-and-sync; drop create-document.
- `test/gremllm/renderer/actions/document_test.cljs` — keep only `set-content`.
- `test/gremllm/renderer/actions/workspace_test.cljs` — update if dispatch payload shapes change.
- `test/gremllm/renderer/ui/document_test.cljs` — empty-state assertion checks for "Open…" button.

**Docs:**
- `src/gremllm/main/README.md` — Data Storage diagram, IPC channel list, hot paths.

---

## Task 1: `path->document-hash` + `document-storage-dir` helpers

**Files:**
- Modify: `src/gremllm/main/io.cljs`
- Test: `test/gremllm/main/io_test.cljs`

- [ ] **Step 1: Write the failing tests**

Add to `test/gremllm/main/io_test.cljs` (create the deftest if file is short; otherwise append):

```clojure
(deftest path->document-hash-test
  (testing "produces a 64-char hex SHA-256 string"
    (let [h (io/path->document-hash "/Users/paul/Desktop/memo.md")]
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h))))

  (testing "is deterministic for the same input"
    (is (= (io/path->document-hash "/tmp/a.md")
           (io/path->document-hash "/tmp/a.md"))))

  (testing "differs for different inputs"
    (is (not= (io/path->document-hash "/tmp/a.md")
              (io/path->document-hash "/tmp/b.md"))))

  (testing "normalizes paths before hashing (trailing slash, .., etc.)"
    (is (= (io/path->document-hash "/tmp/a.md")
           (io/path->document-hash "/tmp/./a.md")))
    (is (= (io/path->document-hash "/tmp/a.md")
           (io/path->document-hash "/tmp/sub/../a.md")))))

(deftest document-storage-dir-test
  (testing "composes <user-data>/User/documents/<hash>"
    (let [user-data "/userdata"
          doc-path  "/Users/paul/Desktop/memo.md"
          hash      (io/path->document-hash doc-path)
          expected  (io/path-join user-data "User" "documents" hash)]
      (is (= expected (io/document-storage-dir user-data doc-path))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `path->document-hash` / `document-storage-dir` unresolved.

- [ ] **Step 3: Implement in `src/gremllm/main/io.cljs`**

Add to the `:require` form: `["crypto" :as crypto] ["path" :as path]` (path may already be present — check first; do not duplicate).

Add functions (place near other path helpers; remove the old `document-file-path` function entirely):

```clojure
(defn path->document-hash
  "SHA-256 hex of the document's normalized absolute path.
   Normalization makes equivalent paths (./, ../, trailing /) hash equally."
  [doc-path]
  (let [normalized (path/resolve doc-path)
        hasher     (.createHash crypto "sha256")]
    (.update hasher normalized)
    (.digest hasher "hex")))

(defn document-storage-dir
  "Per-document private storage dir: <user-data>/User/documents/<hash>/."
  [user-data-dir doc-path]
  (path-join user-data-dir "User" "documents" (path->document-hash doc-path)))
```

Delete `document-file-path` from this file.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS for the new tests. Other tests that referenced `document-file-path` may now fail compilation — that's expected; later tasks address them.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/main/io.cljs test/gremllm/main/io_test.cljs
git commit -m "feat(io): add path->document-hash + document-storage-dir helpers"
```

---

## Task 2: State accessors for active document and user-data-dir

**Files:**
- Modify: `src/gremllm/main/state.cljs`
- Test: `test/gremllm/main/state_test.cljs` (create if missing)

- [ ] **Step 1: Read current `state.cljs` to confirm shape**

Run: `cat src/gremllm/main/state.cljs`
Expected: a small file defining `workspace-dir-path` and `get-workspace-dir`.

- [ ] **Step 2: Write failing tests**

Create or extend `test/gremllm/main/state_test.cljs`:

```clojure
(ns gremllm.main.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.state :as state]))

(deftest accessors-test
  (let [store {:active-document-path "/Users/paul/Desktop/memo.md"
               :user-data-dir        "/userdata"}]
    (testing "get-active-document-path"
      (is (= "/Users/paul/Desktop/memo.md"
             (state/get-active-document-path store))))

    (testing "get-user-data-dir"
      (is (= "/userdata" (state/get-user-data-dir store))))

    (testing "get-storage-dir is derived from user-data-dir + doc path"
      (let [expected-prefix "/userdata/User/documents/"]
        (is (clojure.string/starts-with? (state/get-storage-dir store)
                                          expected-prefix))))

    (testing "get-topics-dir = storage-dir + /topics"
      (is (= (str (state/get-storage-dir store) "/topics")
             (state/get-topics-dir store))))

    (testing "derivations return nil when active doc path is absent"
      (is (nil? (state/get-storage-dir {:user-data-dir "/userdata"})))
      (is (nil? (state/get-topics-dir {:user-data-dir "/userdata"}))))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — accessors unresolved.

- [ ] **Step 4: Rewrite `src/gremllm/main/state.cljs`**

Replace the file contents with:

```clojure
(ns gremllm.main.state
  (:require [gremllm.main.io :as io]))

(def active-document-path [:active-document-path])
(def user-data-dir        [:user-data-dir])

(defn get-active-document-path [store]
  (get-in store active-document-path))

(defn get-user-data-dir [store]
  (get-in store user-data-dir))

(defn get-storage-dir
  "Per-document storage dir. Nil if either active doc or user-data-dir is missing."
  [store]
  (let [doc  (get-active-document-path store)
        udir (get-user-data-dir store)]
    (when (and doc udir)
      (io/document-storage-dir udir doc))))

(defn get-topics-dir
  "Topics dir lives inside the per-document storage dir."
  [store]
  (when-let [sdir (get-storage-dir store)]
    (io/path-join sdir "topics")))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS for `state-test`. Compilation may still fail elsewhere (callers of `workspace-dir-path` / `get-workspace-dir`) — Tasks 4–6 fix those.

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/main/state.cljs test/gremllm/main/state_test.cljs
git commit -m "refactor(state): replace workspace-dir with active-document-path + derived storage paths"
```

---

## Task 3: `write-meta-if-missing!` helper in `effects/workspace.cljs`

**Files:**
- Modify: `src/gremllm/main/effects/workspace.cljs`
- Test: `test/gremllm/main/effects/workspace_test.cljs`

`meta.edn` stores `{:doc-path "<original absolute path>"}`. Only written if the file does not already exist — never overwritten.

- [ ] **Step 1: Write the failing test**

Add to `test/gremllm/main/effects/workspace_test.cljs`:

```clojure
(deftest write-meta-if-missing-test
  (testing "writes meta.edn when absent"
    (with-temp-dir "meta"
      (fn [dir]
        (workspace/write-meta-if-missing! dir "/Users/paul/Desktop/memo.md")
        (let [meta-path (io/path-join dir "meta.edn")
              contents  (cljs.reader/read-string
                          (.readFileSync fs meta-path "utf8"))]
          (is (= {:doc-path "/Users/paul/Desktop/memo.md"} contents))))))

  (testing "does not overwrite existing meta.edn"
    (with-temp-dir "meta"
      (fn [dir]
        (let [meta-path (io/path-join dir "meta.edn")]
          (.writeFileSync fs meta-path (pr-str {:doc-path "/old"}))
          (workspace/write-meta-if-missing! dir "/new")
          (let [contents (cljs.reader/read-string
                           (.readFileSync fs meta-path "utf8"))]
            (is (= {:doc-path "/old"} contents))))))))
```

Make sure the test ns requires:
- `["fs" :as fs]`
- `[cljs.reader]`
- `[gremllm.test-utils :refer [with-temp-dir]]`
- `[gremllm.main.io :as io]`

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `write-meta-if-missing!` unresolved.

- [ ] **Step 3: Add the helper to `src/gremllm/main/effects/workspace.cljs`**

```clojure
(defn write-meta-if-missing!
  "Writes <storage-dir>/meta.edn = {:doc-path doc-path} only if it doesn't exist.
   Creates the storage dir if needed. Never overwrites existing meta."
  [storage-dir doc-path]
  (io/ensure-dir storage-dir)
  (let [meta-path (io/path-join storage-dir "meta.edn")]
    (when-not (io/file-exists? meta-path)
      (.writeFileSync fs meta-path (pr-str {:doc-path doc-path})))))
```

(Reuse the `fs` require already present in the file; if not present, add `["fs" :as fs]`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS for the new test.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/main/effects/workspace.cljs test/gremllm/main/effects/workspace_test.cljs
git commit -m "feat(workspace): add write-meta-if-missing! helper"
```

---

## Task 4: Replace folder-dialog with file-dialog; rewrite `load-and-sync`; delete `create-document`

**Files:**
- Modify: `src/gremllm/main/effects/workspace.cljs`
- Test: `test/gremllm/main/effects/workspace_test.cljs`

- [ ] **Step 1: Read the current file**

Run: `cat src/gremllm/main/effects/workspace.cljs`
Note the current `pick-folder-dialog`, `create-document`, and `load-and-sync` signatures.

- [ ] **Step 2: Update / write the failing tests**

In `test/gremllm/main/effects/workspace_test.cljs`:

(a) **Delete** any tests for `create-document`.
(b) **Rewrite** the test for `load-and-sync` so it reflects the new contract: takes a doc-path; reads `user-data-dir` from store; loads topics from the derived storage dir; writes `meta.edn` if missing.

Example (adapt to existing test scaffolding — `dispatch-spy` may already exist):

```clojure
(deftest load-and-sync-test
  (with-temp-dir "doc-root"
    (fn [doc-root]
      (with-temp-dir "udata"
        (fn [user-data-dir]
          (let [doc-path     (io/path-join doc-root "memo.md")
                _            (.writeFileSync fs doc-path "# Hello")
                store        (atom {:user-data-dir user-data-dir})
                dispatched   (atom [])
                dispatch     (fn [action] (swap! dispatched conj action))]
            (workspace/load-and-sync {:dispatch dispatch :store store}
                                      nil
                                      doc-path)
            (testing "writes meta.edn under <user-data>/User/documents/<hash>"
              (let [sdir      (io/document-storage-dir user-data-dir doc-path)
                    meta-path (io/path-join sdir "meta.edn")]
                (is (.existsSync fs meta-path))))
            (testing "dispatches workspace.actions/opened with document content"
              (let [opened (some #(when (= :workspace.actions/opened (first %)) %)
                                  @dispatched)]
                (is (some? opened))
                ;; payload is implementation-specific; assert content carried through
                (is (re-find #"Hello" (pr-str opened)))))))))))
```

Note: the existing test likely uses a different scaffolding pattern — match what's there. The point is to assert (a) `meta.edn` is written, (b) `workspace.actions/opened` is dispatched, (c) the doc content was loaded.

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `load-and-sync` still uses old signature; `create-document` removed.

- [ ] **Step 4: Update `src/gremllm/main/effects/workspace.cljs`**

(a) **Delete** `pick-folder-dialog` and `create-document` entirely.

(b) **Add** `pick-document-dialog`:

```clojure
(defn pick-document-dialog
  "Show a native file picker filtered to markdown files.
   On success, dispatches [:document.actions/open <abs-path>]."
  [{:keys [dispatch]} _ _]
  (-> (.showOpenDialog dialog
        #js {:properties #js ["openFile"]
             :filters    #js [#js {:name       "Markdown"
                                   :extensions #js ["md" "markdown"]}]})
      (.then (fn [^js result]
               (when-not (.-canceled result)
                 (let [picked (aget (.-filePaths result) 0)]
                   (dispatch [:document.actions/open picked])))))))
```

(c) **Rewrite** `load-and-sync`. The new signature takes a doc-path and reads `user-data-dir` from the store (do not derive it again at every call site):

```clojure
(defn load-and-sync
  "Read the .md file at doc-path, write meta.edn if missing, load topics from
   the per-document storage dir, dispatch :workspace.actions/opened with the
   loaded payload."
  [{:keys [dispatch store]} _ doc-path]
  (let [user-data (state/get-user-data-dir @store)
        sdir      (io/document-storage-dir user-data doc-path)
        topics-d  (io/path-join sdir "topics")
        content   (.readFileSync fs doc-path "utf8")]
    (write-meta-if-missing! sdir doc-path)
    (io/ensure-dir topics-d)
    (let [topics  (io/load-topics topics-d)
          payload (codec/workspace->ipc
                    {:active-document-path doc-path
                     :document             {:content content}
                     :topics               topics})]
      (dispatch [:workspace.actions/opened payload]))))
```

Adjust the codec call to match whatever `codec/workspace->ipc` actually expects in this codebase (read the file first if unsure — it may need a slightly different key shape). The key behavioral requirements:
- Pass `doc-path` (not a folder) downstream.
- Pass the loaded document `content`.
- Pass the `topics` map.

Ensure the ns `:require` includes `[gremllm.main.state :as state]` if not present.

- [ ] **Step 5: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS for the new `load-and-sync-test`. Compilation may fail elsewhere — addressed in Task 5.

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/main/effects/workspace.cljs test/gremllm/main/effects/workspace_test.cljs
git commit -m "feat(workspace): replace folder picker with file picker; load-and-sync takes doc path"
```

---

## Task 5: Rename main workspace actions; delete `main/actions/document.cljs`

**Files:**
- Modify: `src/gremllm/main/actions/workspace.cljs`
- Delete: `src/gremllm/main/actions/document.cljs`
- Delete: `test/gremllm/main/actions/document_test.cljs`
- Modify: `src/gremllm/main/actions.cljs` (registration site for action keys)

Per the advisor invariant: source stays at `actions/workspace.cljs`; the registered keys move to `:document.actions/*`.

- [ ] **Step 1: Read the current file**

Run: `cat src/gremllm/main/actions/workspace.cljs`
Note current functions: `set-directory`, `open-folder`, `pick-folder`, `reload`.

- [ ] **Step 2: Rewrite `src/gremllm/main/actions/workspace.cljs`**

Replace function names and bodies to operate on doc-path instead of folder-path:

```clojure
(ns gremllm.main.actions.workspace)

(defn set-active-document
  "Store the picked document path, then trigger load-and-sync."
  [_store doc-path]
  [[:effects/save [:active-document-path] doc-path]
   [:workspace.effects/load-and-sync doc-path]])

(defn pick
  "User invoked the file picker."
  [_store]
  [[:workspace.effects/pick-document-dialog]])

(defn reload
  "User re-requested loading the active document."
  [store]
  (when-let [doc-path (get-in store [:active-document-path])]
    [[:workspace.effects/load-and-sync doc-path]]))
```

(Adapt to match the project's existing action style — e.g. how `store` is destructured, whether `set` was a generic effect, etc. Read a sibling action file for the exact idiom before writing.)

- [ ] **Step 3: Update registration in `src/gremllm/main/actions.cljs`**

Find the section that registers workspace actions. Replace with:

```clojure
;; Document lifecycle (file source-of-truth in user's filesystem)
(nexus/register-action! :document.actions/open
                        workspace-actions/set-active-document)
(nexus/register-action! :document.actions/pick
                        workspace-actions/pick)
(nexus/register-action! :document.actions/reload
                        workspace-actions/reload)

;; Effects
(nexus/register-effect! :workspace.effects/pick-document-dialog
                        workspace-effects/pick-document-dialog)
(nexus/register-effect! :workspace.effects/load-and-sync
                        workspace-effects/load-and-sync)
```

(Match the actual registration helpers in this file — `nexus/register-action!` etc. may be named differently.)

**Also** remove any registration of `:document.actions/create` and references to `main/actions/document`.

**Also** add a new registration for the userData dispatch (used in Task 6):

```clojure
(nexus/register-action! :app.actions/set-user-data-dir
                        (fn [_store udir]
                          [[:effects/save [:user-data-dir] udir]]))
```

- [ ] **Step 4: Delete the dead file + its test**

```bash
git rm src/gremllm/main/actions/document.cljs
git rm test/gremllm/main/actions/document_test.cljs
```

(If the test file does not exist, the second command is a no-op — skip it.)

- [ ] **Step 5: Run tests to verify compilation + green**

Run: `npm run test`
Expected: PASS. If failures cite "undefined symbol :document.actions/create" in renderer code, that's Task 8/9 territory — leave those failures for now.

- [ ] **Step 6: Commit**

```bash
git add -u
git commit -m "refactor(actions): rename workspace actions to :document.actions/* and delete dead document.cljs"
```

---

## Task 6: IPC channel renames in `core.cljs`; update `acp.effects/send-prompt`; initialize user-data-dir

**Files:**
- Modify: `src/gremllm/main/core.cljs`
- Modify: `src/gremllm/main/actions.cljs` (the `:acp.effects/send-prompt` body)

- [ ] **Step 1: Read both files to locate the relevant sections**

Run: `grep -n 'workspace/\|document/' src/gremllm/main/core.cljs && grep -n 'send-prompt\|workspace-dir\|document-file-path' src/gremllm/main/actions.cljs`

- [ ] **Step 2: In `src/gremllm/main/core.cljs`, rename IPC channels and drop `document/create`**

Apply the renames per spec table:

| Old | New |
|---|---|
| `workspace/pick-folder` | `document/open` |
| `workspace/reload` | `document/reload` |
| `workspace:opened` | `document:opened` |

Drop the `document/create` handler entirely.

Pattern (adapt to actual `ipcMain.handle` / `.on` call style in the file):

```clojure
;; Was:
(.handle ipc-main "workspace/pick-folder"
  (fn [_event] (nexus/dispatch! @!store [[:workspace.actions/pick-folder]])))

;; Becomes:
(.handle ipc-main "document/open"
  (fn [_event] (nexus/dispatch! @!store [[:document.actions/pick]])))
```

```clojure
;; Was:
(.handle ipc-main "workspace/reload"
  (fn [_event] (nexus/dispatch! @!store [[:workspace.actions/reload]])))

;; Becomes (accepts current doc path as arg):
(.handle ipc-main "document/reload"
  (fn [_event _doc-path]
    (nexus/dispatch! @!store [[:document.actions/reload]])))
```

The `_doc-path` arg from the renderer is currently informational — the main process reads the active doc from its own store. Accept it but don't trust it; matches the spec.

Find any `.send` of `"workspace:opened"` and rename to `"document:opened"`.

Find any `.handle` of `"document/create"` and **delete** it.

- [ ] **Step 3: Initialize `user-data-dir` at app ready**

In `core.cljs`, find the function that runs at `app.whenReady()` (likely `initialize-app` or similar). **Before** the window is created, dispatch:

```clojure
(nexus/dispatch! @!store
                  [[:app.actions/set-user-data-dir (.getPath app "userData")]])
```

Order matters: `set-user-data-dir` MUST run before any document load (else `state/get-storage-dir` returns nil).

- [ ] **Step 4: Update `:acp.effects/send-prompt` in `src/gremllm/main/actions.cljs`**

Locate the effect registration. The body currently does something like:

```clojure
(some-> (state/get-workspace-dir @store) io/document-file-path ...)
```

Replace the cwd derivation with:

```clojure
(let [doc-path (state/get-active-document-path @store)
      cwd      (when doc-path (.dirname path doc-path))]
  ;; ... existing acp call, passing cwd
  )
```

Add `["path" :as path]` to the ns `:require` if absent.

**Critical:** cwd is `dirname(doc-path)`, NOT `state/get-storage-dir`. The agent operates against the user's actual document folder, not gremllm's bookkeeping dir.

Search the rest of `actions.cljs` for other call sites referencing `workspace-dir` / `document-file-path` and adjust them analogously (typically: substitute `get-active-document-path` for the doc-path, and `dirname(doc-path)` or `get-storage-dir` per intent — re-derive each call site's intent, don't blanket-replace).

- [ ] **Step 5: Run tests + try to start the app**

Run: `npm run test`
Then: `npm run dev` (in a separate terminal) — confirm Electron launches without an unhandled exception. Quit when verified.

Expected: tests pass; app launches and shows the empty state (Task 10 makes the empty-state UI correct; for now a broken button is acceptable since renderer wiring isn't done).

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/main/core.cljs src/gremllm/main/actions.cljs
git commit -m "feat(ipc): rename workspace channels to document/*; init user-data-dir; ACP cwd from doc path"
```

---

## Task 7: Update preload bridge

**Files:**
- Modify: `resources/public/js/preload.js`

- [ ] **Step 1: Read the current file**

Run: `cat resources/public/js/preload.js`
Identify the bridge methods exposed via `contextBridge.exposeInMainWorld('electronAPI', { ... })`.

- [ ] **Step 2: Apply renames**

| Old method | New method |
|---|---|
| `pickWorkspaceFolder` | `openDocument` |
| `reloadWorkspace(...)` | `reloadDocument(docPath)` |
| `onWorkspaceOpened(cb)` | `onDocumentOpened(cb)` |

Old IPC channel strings get updated correspondingly: `workspace/pick-folder` → `document/open`, `workspace/reload` → `document/reload`, `workspace:opened` → `document:opened`.

**Drop** `createDocument` entirely.

Example diff:

```js
// Was:
pickWorkspaceFolder: () => ipcRenderer.invoke('workspace/pick-folder'),
reloadWorkspace:     (path) => ipcRenderer.invoke('workspace/reload', path),
onWorkspaceOpened:   (cb) => ipcRenderer.on('workspace:opened', (_e, data) => cb(data)),
createDocument:      () => ipcRenderer.invoke('document/create'),

// Becomes:
openDocument:        () => ipcRenderer.invoke('document/open'),
reloadDocument:      (docPath) => ipcRenderer.invoke('document/reload', docPath),
onDocumentOpened:    (cb) => ipcRenderer.on('document:opened', (_e, data) => cb(data)),
```

- [ ] **Step 3: Quick smoke verification**

Run: `npm run dev` — confirm no preload-related console errors at launch. Quit when verified.

- [ ] **Step 4: Commit**

```bash
git add resources/public/js/preload.js
git commit -m "feat(preload): rename workspace bridge methods to document/*"
```

---

## Task 8: Renderer wiring — listener + dispatch updates

**Files:**
- Modify: `src/gremllm/renderer/core.cljs`
- Modify: `src/gremllm/renderer/actions.cljs`
- Modify: `src/gremllm/renderer/actions/workspace.cljs`

The renderer-side action keyword `:workspace.actions/opened` **stays** (per advisor invariant — internal to renderer; no need to rename).

- [ ] **Step 1: Read each file to locate touchpoints**

Run: `grep -n 'Workspace\|workspace' src/gremllm/renderer/core.cljs src/gremllm/renderer/actions.cljs src/gremllm/renderer/actions/workspace.cljs`

- [ ] **Step 2: `src/gremllm/renderer/core.cljs` — rename the listener call**

```clojure
;; Was:
(.onWorkspaceOpened js/window.electronAPI
  (fn [data] (nexus/dispatch! store [[:workspace.actions/opened data]])))

;; Becomes:
(.onDocumentOpened js/window.electronAPI
  (fn [data] (nexus/dispatch! store [[:workspace.actions/opened data]])))
```

The action keyword stays `:workspace.actions/opened`.

- [ ] **Step 3: `src/gremllm/renderer/actions/workspace.cljs` — rename the bridge call in pick-folder**

```clojure
;; Was:
(defn pick-folder [_store]
  [[:effects/promise
    {:promise    (.pickWorkspaceFolder js/window.electronAPI)
     :on-success [[...]]
     :on-error   [[...]]}]])

;; Becomes:
(defn pick-document [_store]
  [[:effects/promise
    {:promise    (.openDocument js/window.electronAPI)
     :on-success [[...]]
     :on-error   [[...]]}]])
```

If `pick-folder` is exported and used elsewhere, rename callers too.

- [ ] **Step 4: `src/gremllm/renderer/actions.cljs` — clean up registrations**

(a) Remove registrations for `:document.actions/create`, `:document.actions/create-success`, `:document.actions/create-error`.
(b) Update the registration that points to `pick-folder` to point to `pick-document`. If the action key was `:workspace.actions/pick-folder`, rename to `:workspace.actions/pick-document` (renderer-internal keyword; spec doesn't constrain this one).
(c) Find `workspace.effects/reload` (or analogous effect) that calls `.reloadWorkspace` and rename to `.reloadDocument`. Pass the active doc path as the arg.

- [ ] **Step 5: Update `test/gremllm/renderer/actions/workspace_test.cljs`**

If `pick-folder` test exists, rename to `pick-document` and update assertions. The `opened-test` and `restore-with-topics-test` should mostly work — the workspace-data shape isn't changing structurally in this task (just the trigger).

Run: `npm run test`
Expected: PASS. If a test still references `pick-folder` or `pickWorkspaceFolder`, update it.

- [ ] **Step 6: Smoke test in dev**

Run: `npm run dev`. Click the (existing) "Open Folder…" menu item — the file picker should open with a `.md` filter. Pick a markdown file. Document content should render. Quit.

Expected: file picker filters to `.md`/`.markdown`; selected file's content renders in the document panel.

- [ ] **Step 7: Commit**

```bash
git add -u
git commit -m "feat(renderer): wire onDocumentOpened listener and openDocument bridge"
```

---

## Task 9: Renderer document actions cleanup

**Files:**
- Modify: `src/gremllm/renderer/actions/document.cljs`
- Modify: `test/gremllm/renderer/actions/document_test.cljs`

- [ ] **Step 1: Read both files**

Run: `cat src/gremllm/renderer/actions/document.cljs test/gremllm/renderer/actions/document_test.cljs`

- [ ] **Step 2: Remove `create`, `create-success`, `create-error`**

Edit `src/gremllm/renderer/actions/document.cljs` to keep only `set-content` (and any other functions that are not part of the deleted create flow). Remove `:require` entries that are no longer used.

- [ ] **Step 3: Update the test**

In `test/gremllm/renderer/actions/document_test.cljs`, delete the deftests for `create`, `create-success`, `create-error`. Keep tests for `set-content`.

- [ ] **Step 4: Run tests**

Run: `npm run test`
Expected: PASS. Compilation errors will surface any missed call sites — fix them.

- [ ] **Step 5: Commit**

```bash
git add -u
git commit -m "refactor(renderer): remove document.actions/create — no longer needed without app-mediated creation"
```

---

## Task 10: Renderer empty-state UI: "Create Document" → "Open…"

**Files:**
- Modify: `src/gremllm/renderer/ui/document.cljs`
- Modify: `test/gremllm/renderer/ui/document_test.cljs`

- [ ] **Step 1: Read both files**

Run: `cat src/gremllm/renderer/ui/document.cljs test/gremllm/renderer/ui/document_test.cljs`

- [ ] **Step 2: Update the failing test**

In `test/gremllm/renderer/ui/document_test.cljs`, the "nil content renders empty state with create button" test:

```clojure
;; Was:
(testing "nil content renders empty state with create button"
  (let [hiccup (doc-ui/render-document nil [] [])]
    (is (some? (lookup/select-one 'button hiccup)))))

;; Becomes:
(testing "nil content renders empty state with Open... button"
  (let [hiccup (doc-ui/render-document nil [] [])]
    (let [btn (lookup/select-one 'button hiccup)]
      (is (some? btn))
      (is (re-find #"(?i)open" (pr-str btn))
          "button should say Open (case-insensitive)")
      (is (some #(= :document.actions/pick (first %))
                ;; dig into :on :click; depends on hiccup shape — adjust selector
                (-> btn second :on :click))
          "clicking dispatches :document.actions/pick"))))
```

Adjust the dispatch-key assertion to whatever action key is actually registered for "open a document via picker" (per Task 5 / Task 8 — `:document.actions/pick` is the recommended name).

- [ ] **Step 3: Run test to verify it fails**

Run: `npm run test`
Expected: FAIL — button still says "Create".

- [ ] **Step 4: Update `src/gremllm/renderer/ui/document.cljs`**

Find the empty-state branch (where content is nil). Replace the "Create Document" button with an "Open…" button:

```clojure
;; Was (approx):
[:div.empty-state
 [:p "No document yet"]
 [:button {:on {:click [[:document.actions/create]]}} "Create Document"]]

;; Becomes:
[:div.empty-state
 [:p "No document open"]
 [:button {:on {:click [[:document.actions/pick]]}} "Open…"]]
```

The exact hiccup shape and styling conventions must match the rest of this file — read sibling components before editing.

- [ ] **Step 5: Run test to verify it passes**

Run: `npm run test`
Expected: PASS.

- [ ] **Step 6: Smoke test**

Run: `npm run dev`. With no document open, the panel should show "No document open" + an "Open…" button. Click it → file picker opens. Quit.

- [ ] **Step 7: Commit**

```bash
git add -u
git commit -m "feat(ui): replace empty-state Create Document with Open… button"
```

---

## Task 11: Menu update — "Open Folder…" → "Open…"

**Files:**
- Modify: `src/gremllm/main/menu.cljs`

- [ ] **Step 1: Read the file**

Run: `cat src/gremllm/main/menu.cljs`

- [ ] **Step 2: Update menu label and dispatched keyword**

```clojure
;; Was:
{:label "Open Folder…"
 :accelerator "CommandOrControl+O"
 :click (fn [] (nexus/dispatch! @!store [[:menu.actions/open-folder]]))}

;; Becomes:
{:label "Open…"
 :accelerator "CommandOrControl+O"
 :click (fn [] (nexus/dispatch! @!store [[:menu.actions/open-document]]))}
```

- [ ] **Step 3: Register the new menu action**

In `src/gremllm/main/actions.cljs`, find any existing `:menu.actions/open-folder` registration and rename it to `:menu.actions/open-document`. Its body should dispatch `:document.actions/pick` (so menu + empty-state button share one path).

```clojure
(nexus/register-action! :menu.actions/open-document
                        (fn [_store] [[:document.actions/pick]]))
```

- [ ] **Step 4: Smoke test**

Run: `npm run dev`. Cmd+O / File menu → "Open…" → file picker appears with `.md` filter. Quit.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/main/menu.cljs src/gremllm/main/actions.cljs
git commit -m "feat(menu): rename Open Folder to Open and dispatch document/pick"
```

---

## Task 12: Update `src/gremllm/main/README.md`

**Files:**
- Modify: `src/gremllm/main/README.md`

- [ ] **Step 1: Read the file**

Run: `cat src/gremllm/main/README.md`

- [ ] **Step 2: Update the Data Storage section**

Replace the workspace-folder diagram with the new layout:

```
<userData>/User/                                # Electron app.getPath("userData")
└── documents/
    └── <sha256(absolute-doc-path)>/
        ├── topics/
        │   └── *.edn
        └── meta.edn      # { :doc-path "/absolute/path/to/user-doc.md" }
```

Add a paragraph: "Users open any `.md` file from anywhere on disk. Gremllm stores its per-document state (topics, meta) under `userData`, keyed by the SHA-256 of the document's normalized absolute path. The user's filesystem is left untouched."

- [ ] **Step 3: Update the IPC channel list**

| Old | New |
|---|---|
| `workspace/pick-folder` | `document/open` |
| `workspace/reload` | `document/reload` |
| `workspace:opened` (event) | `document:opened` (event) |
| `document/create` | (removed) |

- [ ] **Step 4: Update hot-path / entry-point references**

Anywhere it says "workspace folder", clarify the new mental model: one document at a time, opened from any path; storage is private to the app.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/main/README.md
git commit -m "docs(main): document the per-document storage model and renamed IPC channels"
```

---

## Task 13: Full test suite + manual verification

- [ ] **Step 1: Full test suite**

Run: `npm run test:all`
Expected: all unit and integration tests pass.

If any test fails citing `workspace-dir` / `document-file-path` / `pickWorkspaceFolder` / `createDocument`, fix that test (or the leftover usage). Common spots: integration tests that boot the renderer end-to-end.

- [ ] **Step 2: Manual verification (from spec §Verification)**

Run: `npm run dev`. Perform each step:

1. **Open from anywhere:** Create `~/Desktop/test-open-anywhere.md` with content `# Test\n\nHello.`. App → File → **Open…** → pick the file. Document panel renders the content.
2. **Chat persists per document:** Start a topic, send a message, quit the app. Relaunch, **Open…** the same file. Topic and message history restored.
3. **Different documents have independent state:** Create `~/Desktop/a.md` and `~/Desktop/b.md`. Open `a.md`, start a topic. Open `b.md` — no topics. Reopen `a.md` — topic returns.
4. **User folder stays clean:** `ls ~/Desktop` — only the `.md` files; no `topics/`, no `.gremllm/`.
5. **Storage location verified:** `ls "~/Library/Application Support/gremllm/User/documents/"` — one subdir per opened document; each containing `topics/` (if any) and `meta.edn` with the original path. Spot-check `meta.edn` contents with `cat`.
6. **Orphan behavior (accept):** `mv ~/Desktop/a.md ~/Desktop/a-renamed.md`. **Open…** `a-renamed.md` — no topics (expected; old hash dir remains on disk).

- [ ] **Step 3: Final cleanup commit (if anything was tweaked during verification)**

```bash
git add -u
git commit -m "chore: post-verification cleanup" # only if there are changes
```

- [ ] **Step 4: Verify final branch state**

Run: `git log --oneline main..HEAD`
Expected: a clean sequence of ~12 commits, each tagged with the task's intent. No stray uncommitted changes.

---

## Out-of-Scope (do NOT implement)

- Rename/move detection (orphaning is documented behavior).
- Migration of existing `<folder>/topics/` workspaces.
- Recent-documents UI.
- Multi-window / multiple documents open at once.
- Internal `workspace` → `document` namespace rename pass (deferred follow-up).
