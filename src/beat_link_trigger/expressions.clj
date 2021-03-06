(ns beat-link-trigger.expressions
  "A namespace in which user-entered custom expressions will be
  evaluated, which provides support for making them easier to write."
  (:require [overtone.midi :as midi]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder VirtualCdj DeviceUpdate Beat CdjStatus MixerStatus Util
            CdjStatus$TrackSourceSlot CdjStatus$TrackType]
           [java.net InetAddress InetSocketAddress DatagramPacket DatagramSocket]))

(defonce ^{:doc "Holds global variables shared between user expressions."}
  globals (atom {}))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals."
  {:style/indent 1}
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
          (mapcat (fn [[test result]]
                    [(eval (enum-ordinal test)) result])
                  (partition 2 clauses))
          (when (odd? (count clauses))
            (list (last clauses)))))))

(defn track-source-slot
  "Converts the Java enum value representing the slot from which a track
  was loaded to a more convenient Clojure keyword."
  [status]
  (case-enum (.getTrackSourceSlot status)
    CdjStatus$TrackSourceSlot/NO_TRACK   :no-track
    CdjStatus$TrackSourceSlot/CD_SLOT    :cd-slot
    CdjStatus$TrackSourceSlot/SD_SLOT    :sd-slot
    CdjStatus$TrackSourceSlot/USB_SLOT   :usb-slot
    CdjStatus$TrackSourceSlot/COLLECTION :collection
    :unknown))

(defn track-type
  "Converts the Java enum value representing the type of track that
  was loaded to a more convenient Clojure keyword."
  [status]
  (case-enum (.getTrackType status)
    CdjStatus$TrackType/NO_TRACK         :no-track
    CdjStatus$TrackType/CD_DIGITAL_AUDIO :cd-digital-audio
    CdjStatus$TrackType/REKORDBOX        :rekordbox
    :unknown))

(defn track-matches*
  "A convenience function for checking whether a track matches a
  particular source and rekordbox ID number. Compares the source
  player and slot of the track associated with the supplied status
  update with the values found in a media map that is looked up in the
  Beat Link Trigger globals atom using the specified key (the map
  must be structured like the example shown in the
  [Adapting to
  Changes](https://github.com/brunchboy/beat-link-trigger/blob/master/doc/README.adoc#adapting-to-changes)
  section of the user guide, with `:player` and `:slot` entries). If
  the source matches, the id is compared with the specified value, and
  returns the result."
  [status globals source-key rekordbox-id]
  (let [{:keys [player slot]} (get @globals source-key)]
    (and (= (.getTrackSourcePlayer status) player) (= (track-source-slot status) slot)
         (= (.getRekordboxId status) rekordbox-id))))

(defmacro track-matches
  "Convenience macro for use in an Enabled Filter Expression. Checks
  whether the track contained in the current status update matches a
  particular source and rekordbox ID number. Compares the track source
  player and slot with the values found in a media map that is looked
  up in the Beat Link Trigger globals atom using the specified
  key (the map must be structured like the example shown in the
  [Adapting to
  Changes](https://github.com/brunchboy/beat-link-trigger/blob/master/doc/README.adoc#adapting-to-changes)
  section of the user guide, with `:player` and `:slot` entries). If
  the source matches, the id is compared with the specified value, and
  returns the result."
  [source-key rekordbox-id]
  `(track-matches* ~'status ~'globals ~source-key ~rekordbox-id))

(defn find-track*
  "A convenience function for looking for the track described by the
  current status update within a nested map structured as described in
  the [One Trigger per
  Player](https://github.com/brunchboy/beat-link-trigger/blob/master/doc/README.adoc#one-trigger-per-player)
  example in the user guide. The specified key is looked up in the
  Beat Link Trigger globals atom, followed by the player number from
  which the current track was loaded, its slot identifier, and the
  rekordbox ID of the track. Any value found is returned. If any of
  the lookups fails, `nil` is returned.

  If you pass a value for `description-fn`, it will be called with the
  matched track and the result will be used to set the track
  description that Beat Link Trigger should display. A common and easy
  use for this is to pass a keyword, and the value stored at that
  keyword, if any, in the matched track, will be used as the track
  description, since keywords are functions that look themselves up in
  a map given to them as an argument."
  ([status locals globals source-key]
   (find-track* status locals globals source-key nil))
  ([status locals globals source-key description-fn]
   (let [result (get-in @globals [source-key (.getTrackSourcePlayer status) (track-source-slot status)
                                  (.getRekordboxId status)])]
     (when (some? source-key)
       (if (and result (some? description-fn))
         (swap! locals assoc :track-description (description-fn result))
         (swap! locals dissoc :track-description)))
     result)))

(defmacro find-track
  "Convenience macro for use in an Enabled Filter Expression. Looks
  for the track described by the current status update within a nested
  map structured as described in the [One Trigger per
  Player](https://github.com/brunchboy/beat-link-trigger/blob/master/doc/README.adoc#one-trigger-per-player)
  example in the user guide. The specified key is looked up in the
  Beat Link Trigger globals atom, followed by the player number from
  which the current track was loaded, its slot identifier, and the
  rekordbox ID of the track. Any value found is returned. If any of
  the lookups fails, `nil` is returned.

  If you pass a value for `description-fn`, it will be called with the
  matched track and the result will be used to set the track
  description that Beat Link Trigger should display. A common and easy
  use for this is to pass a keyword, and the value stored at that
  keyword, if any, in the matched track, will be used as the track
  description, since keywords are functions that look themselves up in
  a map given to them as an argument."
  ([source-key]
   `(find-track* ~'status ~'locals ~'globals ~source-key nil))
  ([source-key description-fn]
   `(find-track* ~'status ~'locals ~'globals ~source-key ~description-fn)))

(def convenience-bindings
  "Identifies symbols which can be used inside a user expression when
  processing a particular kind of device update, along with the
  expression that will be used to automatically bind that symbol if it
  is used in the expression, and the documentation to show the user
  what the binding is for."
  {DeviceUpdate {:bindings {'address         {:code
                                              '(.getAddress status)
                                              :doc "The address of the device from which this update was received."}
                            'beat?           {:code '(instance? Beat status)
                                              :doc  "Will be <code>true</code> if this update is announcing a new beat."}
                            'beat-within-bar {:code '(.getBeatWithinBar status)
                                              :doc
"The position within a measure of music at which the most recent beat beat fell (a value from 1 to 4, where 1 represents the down beat). This value will be accurate for players when the track was properly configured within rekordbox (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize down beats with players, however, so this value is meaningless when coming from the mixer. The usefulness of this value can be checked with <code>bar-meaningful?</code>"}
                            'bar-meaningful? {:code '(.isBeatWithinBarMeaningful status)
                                              :doc
"Will be <code>true</code> if this update is coming from a device where <code>beat-within-bar</code> can reasonably be expected to have musical significance, because it respects the way a track was configured within rekordbox."}
                            'cdj?            {:code '(or (instance? CdjStatus status)
                                                         (and (instance? Beat status)
                                                              (< (.getDeviceNumber status 17))))
                                              :doc  "Will be <code>true</code> if this update is reporting the status of a CDJ."}
                            'device-name     {:code '(.getDeviceName status)
                                              :doc  "The name reported by the device sending the update."}
                            'device-number   {:code '(.getDeviceNumber status)
                                              :doc  "The player/device number sending the update."}
                            'effective-tempo {:code '(.getEffectiveTempo status)
                                              :doc  "The effective tempo reflected by this update, which reflects both its track BPM and pitch as needed."}
                            'mixer?          {:code '(or (instance? MixerStatus status)
                                                         (and (instance? Beat status)
                                                              (> (.getDeviceNumber status) 32)))
                                              :doc  "Will be <code>true</code> if this update is reporting the status of a Mixer."}
                            'timestamp       {:code '(.getTimestamp status)
                                              :doc  "Records the millisecond at which we received this update."}}}

   Beat         {:inherit  [DeviceUpdate]
                 :bindings {'pitch-multiplier {:code '(Util/pitchToMultiplier (.getPitch status))
                                               :doc
"Represents the current device pitch (playback speed) as a multiplier ranging from 0.0 to 2.0, where normal, unadjusted pitch has the multiplier 1.0, and zero means stopped."}
                            'pitch-percent    {:code '(Util/pitchToPercentage (.getPitch status))
                                               :doc
"Represents the current device pitch (playback speed) as a percentage ranging from -100% to +100%, where normal, unadjusted pitch has the value 0%."}
                            'raw-bpm          {:code '(.getBpm status)
                                               :doc
"Get the raw track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running at 120.5 BPM would be represented by the value 12050."}
                            'raw-pitch        {:code '(.getPitch status)
                                               :doc
"Get the raw device pitch at the time of the beat. This is an integer ranging from 0 to 2,097,152, which corresponds to a range between completely stopping playback to playing at twice normal tempo.
<p>See <code>pitch-multiplier</code> and <code>pitch-percent</code> for more useful forms of this information."}

                            'tempo-master?    {:code '(.isTempoMaster status)
                                               :doc  "Was this beat sent by the current tempo master?"}
                            'track-bpm        {:code '(/ (.getBpm status) 100.0)
                                               :doc
"Get the track BPM at the time of the beat. This is a floating point value ranging from 0.0 to 65,535. See <code>effective-tempo</code> for the speed at which it is currently playing."}}}

   MixerStatus  {:inherit  [DeviceUpdate]
                 :bindings {'raw-bpm       {:code '(.getBpm status)
                                            :doc
"Get the raw track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running at 120.5 BPM would be represented by the value 12050."}
                            'tempo-master? {:code '(.isTempoMaster status)
                                            :doc  "Is this mixer the current tempo master?"}
                            'track-bpm     {:code '(/ (.getBpm status) 100.0)
                                            :doc
"Get the track BPM at the time of the beat. This is a floating point value ranging from 0.0 to 65,535. See <code>effective-tempo</code> for the speed at which it is currently playing."}}}

   CdjStatus    {:inherit  [DeviceUpdate]
                 :bindings {'at-end?            {:code '(.isAtEnd status)
                                                 :doc  "Is the player currently stopped at the end of a track?"}
                            'beat-number        {:code '(.getBeatNumber status)
                                                 :doc
"Identifies the beat of the track that being played. This counter starts at beat 1 as the track is played, and increments on each beat. When the player is paused at the start of the track before playback begins, the value reported is 0.<p> When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player, this information is not available, and the value -1 is reported."}
                            'busy?              {:code '(.isBusy status)
                                                 :doc  "Will be <code>true</code> if the player is doing anything."}
                            'cue-countdown      {:code '(.getCueCountdown status)
                                                 :doc
"How many beats away is the next cue point in the track? If there is no saved cue point after the current play location, or if it is further than 64 bars ahead, the value 511 is returned (and the CDJ will display &ldquo;--.- bars&rdquo;. As soon as there are just 64 bars (256 beats) to go before the next cue point, this value becomes 256. This is the point at which the CDJ starts to display a countdown, which it displays as  &ldquo;63.4 Bars&rdquo;.<p> As each beat goes by, this value decrements by 1, until the cue point is about to be reached, at which point the value is 1 and the CDJ displays &ldquo;00.1 Bars&rdquo;. On the beat on which the cue point was saved the value is 0  and the CDJ displays &ldquo;00.0 Bars&rdquo;. On the next beat, the value becomes determined by the next cue point (if any) in the track."}
                            'cue-countdown-text {:code '(.formatCueCountdown status)
                                                 :doc  "Contains the information from <code>cue-countdown</code> formatted the way it would be displayed on the player."}
                            'cued?              {:code '(.isCued status)
                                                 :doc  "Is the player currently cued (paused at the cue point)?"}
                            'looping?           {:code '(.isLooping status)
                                                 :doc  "Is the player currently playing a loop?"}
                            'on-air?            {:code '(.isOnAir status)
                                                 :doc
"Is the CDJ on the air? A player is considered to be on the air when it is connected to a mixer channel that is not faded out. Only Nexus mixers seem to support this capability."}
                            'paused?            {:code '(.isPaused status)
                                                 :doc  "Is the player currently paused?"}
                            'pitch-multiplier   {:code '(Util/pitchToMultiplier (.getPitch status))
                                                 :doc
"Represents the current device pitch (playback speed) as a multiplier ranging from 0.0 to 2.0, where normal, unadjusted pitch has the multiplier 1.0, and zero means stopped."}
                            'pitch-percent      {:code '(Util/pitchToPercentage (.getPitch status))
                                                 :doc
"Represents the current device pitch (playback speed) as a percentage ranging from -100% to +100%, where normal, unadjusted pitch has the value 0%."}
                            'playing?           {:code '(.isPlaying status)
                                                 :doc  "Is the player currently playing a track?"}
                            'raw-bpm            {:code '(.getBpm status)
                                                 :doc
"Get the raw track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running at 120.5 BPM would be represented by the value 12050."}
                            'raw-pitch          {:code '(.getPitch status)
                                                 :doc
"Get the raw device pitch at the time of the beat. This is an integer ranging from 0 to 2,097,152, which corresponds to a range between completely stopping playback to playing at twice normal tempo. See <code>pitch-multiplier</code> and <code>pitch-percent</code> for more useful forms of this information."}
                            'rekordbox-id       {:code '(.getRekordboxId status)
                                                 :doc "The rekordbox id of the loaded track. Will be 0 if no track is loaded. If the track was loaded from an ordinary audio CD in the CD slot, this will just be the track number."}
                            'synced?            {:code '(.isSynced status)
                                                 :doc  "Is the player currently in Sync mode?"}
                            'tempo-master?      {:code '(.isTempoMaster status)
                                                 :doc  "Is this player the current tempo master?"}
                            'track-bpm          {:code '(/ (.getBpm status) 100.0)
                                                 :doc
"Get the track BPM at the time of the beat. This is a floating point value ranging from 0.0 to 65,535. See <code>effective-tempo</code> for the speed at which it is currently playing."}
                            'track-number       {:code '(.getTrackNumber status)
                                                 :doc "The track number of the loaded track. Identifies the track within a playlist or other scrolling list of tracks in the CDJ's browse interface."}
                            'track-source-player {:code '(.getTrackSourcePlayer status)
                                                :doc "Which player was the track loaded from? Returns the device number, or 0 if there is no track loaded."}
                            'track-source-slot {:code '(track-source-slot status)
                                                :doc "Which slot was the track loaded from? Values are <code>:no-track</code>, <code>:cd-slot</code>, <code>:sd-slot</code>, <code>:usb-slot</code>, or <code>:unknown</code>."}
                            'track-type {:code '(track-type status)
                                                :doc "What kind of track was loaded? Values are <code>:no-track</code>, <code>:cd-digital-audio</code>, <code>:rekordbox</code>, or <code>:unknown</code>."}
                            }}})

(defn bindings-for-update-class
  "Returns the convenience bindings which should be made available for
  the specified device update class, including any inherited ones."
  [c]
  (let [leaf (get convenience-bindings c)
        ancestors (apply merge (map bindings-for-update-class (:inherit leaf)))]
    (merge ancestors (:bindings leaf))))

(defn- gather-convenience-bindings
  "Scans a Clojure form for any occurrences of the convenience symbols
  we know how to make available with useful values, and returns the
  symbols found along with the expressions to which they should be
  bound. If `nil-status?` is `true`, the bindings must be built in a
  way that protects against the possibility of `status` being `nil`."
  [form available-bindings nil-status?]
  (let [result (atom (sorted-map))]
    (clojure.walk/postwalk (fn [elem]
                             (when-let [binding (get available-bindings elem)]
                               (swap! result assoc elem (if nil-status?
                                                          `(when ~'status ~(:code binding))
                                                          (:code binding)))))
                           form)
    (apply concat (seq @result))))

(defmacro ^:private wrap-user-expression
  "Takes a Clojure form containing a user expression, adds bindings
  for any convenience symbols that were found in it, and builds a
  function that accepts a status object, binds the convenience symbols
  based on the status, and returns the results of evaluating the user
  expression in that context. If `nil-status?` is `true`, the bindings
  must be built in a way that protects against the possibility of
  `status` being `nil`."
  [body available-bindings nil-status?]
  (let [bindings (gather-convenience-bindings body available-bindings nil-status?)]
    `(fn ~'[status {:keys [locals] :as trigger-data} globals]
       ~(if (seq bindings)
          `(let [~@bindings]
             ~body)
          body))))

(defn build-user-expression
  "Takes a string that a user has entered as a custom expression, adds
  bindings for any convenience symbols that were found in it, and
  builds a function that accepts a status object, binds the
  convenience symbols based on the status, and returns the results of
  evaluating the user expression in that context. If `nil-status?` is
  `true`, the bindings must be built in a way that protects against
  the possibility of `status` being `nil`."
  [expr available-bindings nil-status?]
  (binding [*ns* (the-ns 'beat-link-trigger.expressions)]
    (let [body (read-string (str "(do " expr "\n)"))]
      (eval `(wrap-user-expression ~body ~available-bindings ~nil-status?)))))
