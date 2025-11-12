(ns gremllm.main.effects.attachment
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [gremllm.main.io :as io]
            [clojure.string :as str]))

(def ^:private attachments-subdir "attachments")

(defn attachments-dir-path
  "Build path to workspace attachments directory."
  [workspace-dir]
  (io/path-join workspace-dir attachments-subdir))

(defn compute-file-hash
  "Compute SHA256 hash of file contents. Returns first 8 chars."
  [file-path]
  (let [content (.readFileSync fs file-path)
        hash-obj (.createHash crypto "sha256")]
    (.update hash-obj content)
    (-> (.digest hash-obj "hex")
        (subs 0 8))))

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

(defn store-attachment
  "Copy file to workspace attachments folder with content-addressed name.
   Returns AttachmentRef map: {:ref :name :mime-type :size}"
  [workspace-path file-path]
  (let [hash (compute-file-hash file-path)
        original-name (io/path-basename file-path)
        stored-name (str hash "-" original-name)
        attachments-dir (attachments-dir-path workspace-path)
        dest-path (io/path-join attachments-dir stored-name)
        file-stats (.statSync fs file-path)]
    ;; Ensure attachments directory exists
    (io/ensure-dir attachments-dir)
    ;; Copy file to attachments folder
    (.copyFileSync fs file-path dest-path)
    ;; Return attachment reference
    {:ref hash
     :name original-name
     :mime-type (mime-type-from-extension original-name)
     :size (.-size file-stats)}))

(defn load-attachment-content
  "Load attachment file by hash prefix. Returns Buffer or nil if not found."
  [workspace-path hash-prefix]
  (let [attachments-dir (attachments-dir-path workspace-path)]
    (when (io/file-exists? attachments-dir)
      (let [files (io/read-dir attachments-dir)
            matching-file (first (filter #(str/starts-with? % hash-prefix) files))]
        (when matching-file
          (.readFileSync fs (io/path-join attachments-dir matching-file)))))))

(defn attachment-ref->inline-data
  "Convert attachment reference + content Buffer to Gemini inline_data format.
   Pure function: transforms data shape for API."
  [attachment-ref content-buffer]
  (let [base64-data (.toString content-buffer "base64")]
    {:inline_data {:mime_type (:mime-type attachment-ref)
                   :data base64-data}}))
