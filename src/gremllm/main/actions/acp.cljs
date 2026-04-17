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

(defn- locator-label [{:keys [start-block end-block]}]
  (let [start (block-label start-block)
        end   (block-label end-block)]
    (if (= start end) start (str start " -> " end))))

(defn- blockquote
  "Prefix every line of s with '      > ' so multi-line content preserves the
   References list shape without leaking Clojure quoting conventions."
  [s]
  (->> (str/split-lines s)
       (map #(str "      > " %))
       (str/join "\n")))

(defn- render-excerpt [idx {:keys [text locator]}]
  (let [{:keys [start-block]} locator]
    (str "  [" (inc idx) "] " (locator-label locator) "\n"
         "      text:\n" (blockquote text) "\n"
         "      block context:\n" (blockquote (:block-text-snippet start-block)))))

(defn- render-references [excerpts]
  (str "\nReferences:\n"
       (->> excerpts
            (map-indexed render-excerpt)
            (str/join "\n"))))

(defn- prompt-body
  "Compose the prompt text body: user text followed by a References section
   when the message carries excerpts."
  [{:keys [text context]}]
  (cond-> text
    (seq (:excerpts context)) (str (render-references (:excerpts context)))))

(defn prompt-content-blocks
  "Build ACP prompt content blocks from a structured user message and optional
   document path. Excerpts are rendered into a References section appended to
   the user text in the single text block."
  [message document-path]
  (cond-> [{:type "text" :text (prompt-body message)}]
    document-path (conj {:type "resource_link"
                         :uri  (io/path->file-uri document-path)
                         :name (io/path-basename document-path)})))
