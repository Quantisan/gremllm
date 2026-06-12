(ns gremllm.renderer.state.acp
  "Tracks which topics have a live ACP session this app run.
   Persisted session ids outlive the in-process agent, so each run must
   resume (or create) a session exactly once per topic.")

(def live-topics-path [:acp :live-topics])

(defn live? [state topic-id]
  (contains? (get-in state live-topics-path #{}) topic-id))

(defn with-topic-live [state topic-id]
  (conj (get-in state live-topics-path #{}) topic-id))
