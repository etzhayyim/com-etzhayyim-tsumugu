(ns tsumugu.content
  "Read the canonical Spirit in Physics story-bible (single source of truth)
  so tsumugu's generation is the novel's content, not invented. JVM authoring
  helper — mirrors `sip.lore`/`sip.storyboard` (kami-app-sip-clj)'s
  read-only-from-the-IP-repo pattern.

  Source repo: ../../com-junkawasaki/org-spirit-in-physics-comics (override
  with $SIP_IP_ROOT — same env var `sip.lore`/`sip.storyboard`/`sip.render`
  use in kami-app-sip-clj, so one checkout serves both deployables).
  Everything degrades gracefully: a missing file just yields no chapters,
  never an exception — tsumugu should never crash because the content repo
  isn't checked out."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ip-root
  (or (System/getenv "SIP_IP_ROOT")
      "../../com-junkawasaki/org-spirit-in-physics-comics"))

(defn- read-edn [f]
  (when (.exists ^java.io.File f) (edn/read-string (slurp f))))

(defn- chapter-num [dir-name]
  (some-> (re-find #"chapter0*(\d+)" dir-name) second Long/parseLong))

(defn- panel->map [chapter-id page panel]
  {:id (:gh/panelId panel)
   :chapter chapter-id
   :page (some-> (:gh/pageNumber page) long)
   :layout (:layout page)
   :size (:gh/size panel)
   :camera (:camera panel)
   :location (:location panel)
   :description (:gh/description panel)
   :emotion (:emotion panel)
   :colorNote (:colorNote panel)
   :dialogue (->> (:dialogue panel [])
                  (mapv (fn [d] {:speaker (:speaker d) :text (:text d)})))
   :narration (:narration panel)
   :characters (->> (:characters panel [])
                     (map #(-> (str %) (str/replace #"^character:" "") keyword))
                     vec)})

(defn- chapter-dir->chapter [vol-slug dir]
  (let [n (chapter-num (.getName ^java.io.File dir))
        sb (read-edn (io/file dir "storyboard.edn"))
        ep (read-edn (io/file dir "episode.edn"))
        id (str vol-slug "/ch" (format "%02d" (or n 0)))
        title (or (:schema/name sb) (:dct/title ep) (:dct/title_ja ep) id)
        panels (mapcat (fn [page] (map #(panel->map id page %) (:panels page)))
                        (:gh/pages sb))]
    (when (or sb ep)
      {:id id :chapter n :title title :storyboard (vec panels)})))

(defn- volume-dirs []
  (let [proj (read-edn (io/file ip-root "PROJECT.edn"))]
    (for [v (:gh/volumes proj)
          :let [slug (-> (str (:schema/filePath v)) (str/replace #"/+$" "") (str/replace #"^volumes/" ""))
                vdir (io/file ip-root "volumes" slug)]
          :when (.exists vdir)]
      [slug vdir])))

(defn chapters
  "Every chapter with a storyboard or episode script, across all volumes —
  the content tsumugu is allowed to generate from. Returns [] (never throws)
  if the IP repo isn't checked out at $TSUMUGU_IP_ROOT."
  []
  (vec
   (mapcat (fn [[slug vdir]]
             (->> (.listFiles ^java.io.File vdir)
                  (filter #(.isDirectory ^java.io.File %))
                  (filter #(re-find #"chapter\d+" (.getName ^java.io.File %)))
                  (sort-by #(chapter-num (.getName ^java.io.File %)))
                  (keep #(chapter-dir->chapter slug %))))
           (volume-dirs))))

(defn chapter-by-id [id]
  (first (filter #(= id (:id %)) (chapters))))
