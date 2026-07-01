(ns tsumugu.render
  "Spirit in Physics render — the WORK-SPECIFIC facade over the generic
  mangaka render commons (`kami.mangaka.render`, ADR-2606282100). Mirrors
  `sip.render` (kami-app-sip-clj) — kept in sync by hand since tsumugu (the
  actor) and kami-app-sip-clj (the render/game app) are separate deployables
  reading the same IP repo; duplicating this small work-specific mapper table
  here keeps tsumugu's own dependency footprint self-contained (containment).

  This ns keeps only what is *about Spirit in Physics*: the Nei light/embodied
  focal rule, the 静寂→serene emotion table, the 事務所→:schwa-office location
  map. Word-budget + STYLE-FIRST composition stay in the commons."
  (:require [clojure.string :as str]
            [kami.mangaka.render :as km]))

(def ^:private nei-light-cues
  ["ポッド" "光体" "発光" "半透明" "粒子" "光の" "明滅" "覚醒" "起動"
   "pod" "glow" "translucent" "luminous" "light field" "emergence" "awaken"])

(defn- nei-form
  "Pick Nei's embodied (:nei) vs light-figure (:nei-light) anchor from the
  panel's prose. Light-form for pod/awakening/abstract beats; embodied
  otherwise."
  [{:keys [description emotion colorNote location]}]
  (let [blob (str/lower-case (str description " " emotion " " colorNote " " location))]
    (if (some #(str/includes? blob (str/lower-case %)) nei-light-cues) :nei-light :nei)))

(defn focal-character
  "One character per panel: the first dialogue speaker if in the cast, else
  the first listed. Applies the nei→nei-light swap for awakening/ghost-space
  beats. nil if none."
  [panel]
  (let [chars (:characters panel)
        sp    (some-> (first (:dialogue panel)) :speaker str str/lower-case keyword)
        pick  (or (some #{sp} chars) (first chars))]
    (when pick (if (= pick :nei) (nei-form panel) pick))))

(def ^:private emotion->tag
  {"静寂" "serene" "静けさ" "serene" "目覚め" "awakening mood"
   "温もり" "warm mood" "温かさ" "warm mood" "やわらか" "tender"
   "戸惑い" "puzzled expression" "問い" "questioning expression"
   "歓び" "joyful expression" "喜び" "joyful expression" "受容" "gentle expression"
   "緊張" "tense" "接近" "intimate" "非分離" "intimate"
   "見守り" "watchful expression" "充足" "content expression"
   "発見" "wonder" "驚き" "surprised expression" "歓喜" "joyful expression"
   "ためらい" "hesitant expression" "余韻" "lingering quiet"})

(defn mood-tags [emotion]
  (->> emotion->tag
       (keep (fn [[jp en]] (when (str/includes? (str emotion) jp) en)))
       distinct (take 2) vec))

(defn env-key [location]
  (let [l (str location)]
    (cond
      (re-find #"事務所|office|デスク|オフィス" l) :schwa-office
      (re-find #"キッチン|アパート|kitchen|apartment|テーブル|窓際|室内" l) :tamaki-apartment
      (re-find #"遊歩道|並木|沿い|walkway|path|道" l) :canal-path
      (re-find #"運河|水の都|canal|水面" l) :water-city
      :else :water-city)))

(defn compose
  "Panel map + SIP anchors → render spec, by injecting SIP's three work
  mappers into the generic `km/compose`."
  [{:keys [anchors panel mappers]}]
  (km/compose {:anchors anchors
               :panel panel
               :mappers (merge {:focal-character focal-character
                                :location->env   env-key
                                :emotion->tags   mood-tags}
                               mappers)}))
