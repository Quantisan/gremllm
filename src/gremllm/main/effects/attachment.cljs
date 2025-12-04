(ns gremllm.main.effects.attachment
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [gremllm.main.io :as io]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [clojure.string :as str]))

(def ^:private attachments-subdir "attachments")

(defn- attachments-dir-path
  "Build path to workspace attachments directory."
  [workspace-dir]
  (io/path-join workspace-dir attachments-subdir))

(defn- hash-content
  "Compute SHA256 hash of content buffer. Returns first 8 chars."
  [content-buffer]
  (let [hash-obj (.createHash crypto "sha256")]
    (.update hash-obj content-buffer)
    (-> (.digest hash-obj "hex")
        (subs 0 8))))

(defn compute-file-hash
  "Effect: read file and compute SHA256 hash. Returns first 8 chars."
  [file-path]
  (let [content (.readFileSync fs file-path)]
    (hash-content content)))

;; TODO: MIME type inference has limited coverage - unknown extensions default to
;; application/octet-stream, which some APIs (e.g., Gemini) reject. Root cause:
;; browser File API provides correct MIME type in renderer, but it's not transmitted
;; over IPC (only file paths sent). Should pass file metadata instead of just paths.
(defn- mime-type-from-extension
  "Infer MIME type from file extension. Returns basic image/document types."
  [filename]
  (let [ext (-> filename
                (str/lower-case)
                (str/split #"\.")
                (last))]
    (case ext
      "png"  "image/png"
      "jpg"  "image/jpeg"
      "jpeg" "image/jpeg"
      "gif"  "image/gif"
      "webp" "image/webp"
      "pdf"  "application/pdf"
      "txt"  "text/plain"
      "md"   "text/markdown"
      "application/octet-stream")))

(defn- build-stored-filename
  "Build content-addressed filename from hash and original name."
  [file-hash original-name]
  (str file-hash "-" original-name))

(defn- build-attachment-paths
  "Calculate storage paths for an attachment.
   Returns {:attachments-dir :dest-path :stored-name}"
  [workspace-path file-hash original-name]
  (let [stored-name (build-stored-filename file-hash original-name)
        attachments-dir (attachments-dir-path workspace-path)
        dest-path (io/path-join attachments-dir stored-name)]
    {:attachments-dir attachments-dir
     :dest-path dest-path
     :stored-name stored-name}))

(defn- build-attachment-ref
  "Construct AttachmentRef from components."
  [file-hash original-name mime-type file-size]
  {:ref file-hash
   :name original-name
   :mime-type mime-type
   :size file-size})

(defn store-attachment
  "Helper: copy file to workspace attachments folder with content-addressed name.
   Returns validated AttachmentRef map: {:ref :name :mime-type :size}"
  [workspace-path file-path]
  ;; I/O: gather file metadata
  (let [file-hash (compute-file-hash file-path)
        original-name (io/path-basename file-path)
        file-stats (.statSync fs file-path)

        ;; Pure: compute storage paths
        paths (build-attachment-paths workspace-path file-hash original-name)

        ;; I/O: perform file operations
        _ (io/ensure-dir (:attachments-dir paths))
        _ (.copyFileSync fs file-path (:dest-path paths))

        ;; Pure: construct result
        mime-type (mime-type-from-extension original-name)
        attachment-ref (build-attachment-ref file-hash original-name
                                             mime-type (.-size file-stats))]

    ;; Validate at boundary before returning
    (m/coerce schema/AttachmentRef attachment-ref)))

(defn load-attachment-content
  "Load attachment file by hash prefix. Returns Buffer or nil if not found."
  [workspace-path hash-prefix]
  (let [attachments-dir (attachments-dir-path workspace-path)]
    (when (io/file-exists? attachments-dir)
      (let [files (io/read-dir attachments-dir)
            matching-file (first (filter #(str/starts-with? % hash-prefix) files))]
        (when matching-file
          (.readFileSync fs (io/path-join attachments-dir matching-file)))))))

(defn store-all
  "Store multiple files to workspace attachments folder.
   Returns vector of AttachmentRefs."
  [workspace-path file-paths]
  (mapv #(store-attachment workspace-path %) file-paths))

(defn load-all-content
  "Load content for all attachment refs.
   Returns vector of [ref buffer] pairs."
  [workspace-path refs]
  (mapv (fn [ref] [ref (load-attachment-content workspace-path (:ref ref))]) refs))
