(ns ciwi.render.movie
  (:require [ciwi.render.graph :as render-graph]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

(defn frame-name
  "Return a stable zero-padded frame filename."
  ([index]
   (frame-name index "png"))
  ([index ext]
   (format "frame-%06d.%s" (long index) ext)))

(defn frame-path
  "Return the stable frame path for an output directory and index."
  ([output-dir index]
   (frame-path output-dir index "png"))
  ([output-dir index ext]
   (.getPath (io/file output-dir (frame-name index ext)))))

(defn write-graph-frame!
  "Render one graph PNG frame using the generic graph renderer."
  ([output-dir index g]
   (write-graph-frame! output-dir index g {}))
  ([output-dir index g opts]
   (render-graph/render-png! g (frame-path output-dir index) opts)))

(defn ffmpeg-available?
  []
  (try
    (zero? (:exit (sh/sh "ffmpeg" "-version")))
    (catch java.io.IOException _
      false)))

(defn frames->mp4!
  "Turn stable PNG frames into an MP4 with ffmpeg when ffmpeg is available."
  ([frame-dir mp4-path]
   (frames->mp4! frame-dir mp4-path {}))
  ([frame-dir mp4-path {:keys [framerate pattern]
                        :or {framerate 2
                             pattern "frame-%06d.png"}}]
   (if-not (ffmpeg-available?)
     {:status :unavailable
      :reason :ffmpeg-not-found
      :frame-dir (.getPath (io/file frame-dir))
      :mp4-path (.getPath (io/file mp4-path))}
     (let [input-pattern (.getPath (io/file frame-dir pattern))
           output-file (io/file mp4-path)
           _ (io/make-parents output-file)
           result (sh/sh "ffmpeg"
                         "-y"
                         "-framerate" (str framerate)
                         "-i" input-pattern
                         "-vf" "scale=trunc(iw/2)*2:trunc(ih/2)*2"
                         "-pix_fmt" "yuv420p"
                         (.getPath output-file))]
       (if (zero? (:exit result))
         {:status :ok
          :frame-dir (.getPath (io/file frame-dir))
          :mp4-path (.getPath output-file)}
         {:status :failed
          :frame-dir (.getPath (io/file frame-dir))
          :mp4-path (.getPath output-file)
          :exit (:exit result)
          :out (:out result)
          :err (:err result)})))))
