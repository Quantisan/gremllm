(ns gremllm.schema.codec.acp-permission
  "Pure permission policy for ACP. Operates on coerced CLJS maps.
   Use gremllm.schema.codec/acp-permission-request-from-js and
   acp-session-update-from-js to produce inputs for these fns."
  (:require ["path" :as path-module]))

(defn requested-tool-name
  "Return the tool name from a coerced AcpPermissionToolCall, or nil."
  [{:keys [tool-name]}]
  (when (seq tool-name) tool-name))

(defn requested-path
  "Return the file path from a coerced AcpPermissionToolCall's :raw-input, or nil."
  [{:keys [raw-input]}]
  (or (:path raw-input) (:file-path raw-input)))

(defn within-root?
  "True when candidate-path is equal to or nested under root-path."
  [candidate-path root-path]
  (let [relative (.relative path-module root-path candidate-path)]
    (or (= relative "")
        (and (not (.startsWith relative ".."))
             (not (.isAbsolute path-module relative))))))

(defn remember-tool-name
  "Return an updated tool-names map with tool name from session-update recorded.
   Only records when both tool-call-id and tool-name are present in the update."
  [tool-names session-update]
  (let [{:keys [tool-call-id]} (:update session-update)
        tool-name (get-in session-update [:update :meta :claude-code :tool-name])]
    (if (and tool-call-id (seq tool-name))
      (assoc tool-names tool-call-id tool-name)
      tool-names)))

(defn enrich-permission-params
  "Return permission-request with :tool-name resolved from tool-names map when absent.
   Looks up tool-call-id in tool-names as a fallback when :tool-name is missing."
  [tool-names permission-request]
  (let [{:keys [tool-call]} permission-request
        {:keys [tool-call-id tool-name]} tool-call
        tracked-name (or (when (seq tool-name) tool-name)
                         (get tool-names tool-call-id))]
    (if (seq tracked-name)
      (assoc-in permission-request [:tool-call :tool-name] tracked-name)
      permission-request)))

(defn- select-option
  "Select the first option matching a preferred kind, or apply fallback-fn to all options."
  [options preferred-kinds fallback-fn]
  (or (some (fn [kind] (first (filter #(= (:kind %) kind) options))) preferred-kinds)
      (fallback-fn options)))

(defn resolve-permission
  "Determine permission outcome from a coerced AcpPermissionRequest and session cwd.
   Returns {:outcome {:outcome \"selected\" :option-id \"...\"}}
   or      {:outcome {:outcome \"cancelled\"}}."
  [permission-request session-cwd]
  (let [{:keys [options tool-call]} permission-request
        {:keys [kind]} tool-call]
    (if (empty? options)
      {:outcome {:outcome "cancelled"}}
      (case kind
        "read"
        (let [opt (select-option options ["allow_always" "allow_once"] first)]
          {:outcome {:outcome "selected" :option-id (:option-id opt)}})

        "edit"
        ;; Critical workflow nuance: approving an in-workspace edit/write means
        ;; "allow the agent to complete the proposal path", not "write the file
        ;; immediately". The ACP bridge keeps writeTextFile as a dry-run no-op,
        ;; so the successful path is still non-mutating. Rejecting changes the
        ;; semantics entirely: Claude reports that the user refused the tool, and
        ;; the proposal step fails instead of returning a reviewable diff.
        ;; Prefer allow_once so every Edit re-enters this resolver and gets a
        ;; fresh path check. allow_always would create a session-scoped rule
        ;; keyed only on toolName, bypassing the workspace-root guard for all
        ;; subsequent edits—including ones outside the workspace root.
        (let [raw-path       (requested-path tool-call)
              normalized-cwd (when session-cwd (.resolve path-module session-cwd))
              normalized-req (when raw-path (.resolve path-module raw-path))]
          (if (and normalized-cwd normalized-req (within-root? normalized-req normalized-cwd))
            (let [opt (select-option options ["allow_once" "allow_always"] first)]
              {:outcome {:outcome "selected" :option-id (:option-id opt)}})
            (let [opt (select-option options ["reject_once" "reject_always"] last)]
              {:outcome {:outcome "selected" :option-id (:option-id opt)}})))

        ;; Default: reject.
        (let [opt (select-option options ["reject_once" "reject_always"] last)]
          {:outcome {:outcome "selected" :option-id (:option-id opt)}})))))
