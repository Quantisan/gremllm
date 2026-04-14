(ns gremllm.main.actions.acp
  "Pure actions for ACP prompt construction"
  (:require [clojure.string :as str]
            [gremllm.main.io :as io]))

(defn- block-label
  "Compact label for a BlockRef like p2, li4, h1, code5."
  [{:keys [kind index]}]
  (let [prefix (case kind
                 :heading "h"
                 :paragraph "p"
                 :list-item "li"
                 :code-block "code"
                 :blockquote "bq"
                 :table "tbl"
                 (name kind))]
    (str prefix index)))

(defn- locator-label [{:keys [start-block end-block start-offset end-offset]}]
  (let [start (block-label start-block)
        end (block-label end-block)
        base (if (= start end) start (str start " -> " end))]
    (if (and start-offset end-offset (= start end))
      (str base " offset " start-offset "-" end-offset)
      base)))

(defn- render-excerpt [idx {:keys [text locator]}]
  (let [{:keys [start-block]} locator]
    (str "  [" (inc idx) "] " (locator-label locator) "\n"
         "      text: " (pr-str text) "\n"
         "      block context: " (pr-str (:block-text-snippet start-block)))))

(defn- render-references [excerpts]
  (str "\nReferences:\n"
       (->> excerpts
            (map-indexed render-excerpt)
            (str/join "\n"))))

(defn prompt-content-blocks
  "Build ACP prompt content blocks from a structured user message and optional
   document path. Excerpts are rendered into a References section appended to
   the user text in the single text block."
  [message document-path]
  (let [{:keys [text context]} message
        excerpts (:excerpts context)
        body (if (seq excerpts)
               (str text (render-references excerpts))
               text)]
    (cond-> [{:type "text" :text body}]
    document-path (conj {:type "resource_link"
                         :uri  (io/path->file-uri document-path)
                         :name (io/path-basename document-path)}))))
