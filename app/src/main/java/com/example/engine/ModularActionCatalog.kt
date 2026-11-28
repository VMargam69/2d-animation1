package com.example.engine

import com.example.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 100% offline Pro Catalog of 180 Desi & Story modular character presets across 3 independent tracks:
 * - Expressions (53 presets: 45 Desi/Story + 8 legacy `act_face_*`)
 * - Hand Parts (88 presets: 80 Desi/Story + 8 legacy `act_hand_*`)
 * - Leg Parts (39 presets: 31 Desi/Story + 8 legacy `act_leg_*`)
 *
 * ANATOMICAL SIGN CONVENTION (100% aligned with `ProceduralRigRenderer` & `SpriteAtlasRigRenderer` Android Canvas):
 * - Left Arm (mounted at `-X`):
 *   `leftArmDelta > 0` swings outward-left/up; `leftForearmDelta < 0` bends elbow INWARD across chest/face.
 * - Right Arm (mounted at `+X`):
 *   `rightArmDelta < 0` swings outward-right/up; `rightForearmDelta > 0` bends elbow INWARD across chest/face.
 * - Legs:
 *   `legDelta < 0` swings thigh forward/up-right; `kneeDelta > 0` bends shin downward/back.
 *
 * ZERO-POP SEAMLESS LOOPS:
 * Every preset evaluates smoothly & periodically across `p in 0f..1f` (`sample(0f) == sample(1f)`)
 * using harmonic breathing, follow-through lag, and smooth C1 curves so looping actions never snap!
 */
object ModularActionCatalog {

    val allActions: List<ModularActionDef> = listOf(
        // ═══════════════════════════════════════════════════════════════
        // EXPRESSIONS (53 presets: 45 Desi/Story + 8 legacy act_face_*)
        // ═══════════════════════════════════════════════════════════════
        ModularActionDef("expr_angry_face", "Angry Face Expression", ModularActionTrack.FACIAL, 26, "Fierce scowl & clenched jaw"),
        ModularActionDef("expr_angry_fight", "Angry Fight Expression", ModularActionTrack.FACIAL, 20, "Intense battle glare & shout"),
        ModularActionDef("expr_angry_lipsync", "Angry Lipsing Expression", ModularActionTrack.FACIAL, 24, "Shouting angry dialogue cadence"),
        ModularActionDef("expr_boat_drive_stand", "Boat Drive Stand Expression", ModularActionTrack.FACIAL, 28, "Focused rowing concentration"),
        ModularActionDef("expr_bored", "Bored Face Expression", ModularActionTrack.FACIAL, 32, "Heavy droopy lids & slow sigh"),
        ModularActionDef("expr_carefree", "Carefree Face Expression", ModularActionTrack.FACIAL, 28, "Breezy smile & gentle head sway"),
        ModularActionDef("expr_carrying_heavy_bori", "Carrying Heavy Bori Expression", ModularActionTrack.FACIAL, 26, "Strained heavy lifting grit"),
        ModularActionDef("expr_clapping", "Clapping Expression", ModularActionTrack.FACIAL, 24, "Joyful applause grin & nod"),
        ModularActionDef("expr_cleaning_field", "Cleaning Field Expression", ModularActionTrack.FACIAL, 28, "Steady hardworking field gaze"),
        ModularActionDef("expr_combing_head", "Combing Head Expression", ModularActionTrack.FACIAL, 26, "Pleasant grooming mirror smile"),
        ModularActionDef("expr_coughing", "Coughing Expression", ModularActionTrack.FACIAL, 22, "Squinted cough jolts"),
        ModularActionDef("expr_door_open_slowly", "Door Open Slowly Expression", ModularActionTrack.FACIAL, 30, "Cautious alert peeking gaze"),
        ModularActionDef("expr_doubt", "Doubt Face Expression", ModularActionTrack.FACIAL, 28, "Raised brow & skeptical squint"),
        ModularActionDef("expr_drinking_water", "Drink Water From Glass Expression", ModularActionTrack.FACIAL, 26, "Tilting back & gulping water"),
        ModularActionDef("expr_eating", "Eating Burger Expression", ModularActionTrack.FACIAL, 24, "Rhythmic chewing expression"),
        ModularActionDef("expr_fear", "Fear Expression", ModularActionTrack.FACIAL, 22, "Wide trembling eyes & gasp"),
        ModularActionDef("expr_fed_up", "Fed Up Expression", ModularActionTrack.FACIAL, 28, "Exasperated eye-roll & frown"),
        ModularActionDef("expr_gods_ashirwaad", "Gods Ashirwaad Expression", ModularActionTrack.FACIAL, 30, "Serene divine blessing smile"),
        ModularActionDef("expr_hammering", "Hammering Expression", ModularActionTrack.FACIAL, 22, "Focused grit on each strike"),
        ModularActionDef("expr_hammer_nail", "Hammer Nail Expression", ModularActionTrack.FACIAL, 22, "Precision squinted aim & strike"),
        ModularActionDef("expr_hand_on_forehead", "Hand On Forehead Face Expression", ModularActionTrack.FACIAL, 28, "Stressed dismay & closed eyes"),
        ModularActionDef("expr_hiding", "Hiding Expression", ModularActionTrack.FACIAL, 28, "Nervous peeking side-to-side"),
        ModularActionDef("expr_hold_lantern_search", "Hold Lantern Search Expression", ModularActionTrack.FACIAL, 30, "Alert dark-search wide gaze"),
        ModularActionDef("expr_hold_plate_eating", "Hold Plate Eating Expression", ModularActionTrack.FACIAL, 26, "Happy chewing mealtime gaze"),
        ModularActionDef("expr_idea", "Idea Face Expression", ModularActionTrack.FACIAL, 24, "Eureka sparkle & bright grin"),
        ModularActionDef("expr_idle_angry", "Idle Angry Expression", ModularActionTrack.FACIAL, 28, "Stewing furrowed stare"),
        ModularActionDef("expr_idle_happy", "Idle Happy Expression", ModularActionTrack.FACIAL, 30, "Warm relaxed smile & soft blink"),
        ModularActionDef("expr_idle_sad", "Idle Sad Expression", ModularActionTrack.FACIAL, 30, "Downcast gaze & gentle droop"),
        ModularActionDef("expr_jogging", "Jogging Expression", ModularActionTrack.FACIAL, 20, "Determined rhythmic jog breath"),
        ModularActionDef("expr_jump_down", "Jump Down Expression", ModularActionTrack.FACIAL, 22, "Braced landing focus expression"),
        ModularActionDef("expr_jump_up", "Jump Up Expression", ModularActionTrack.FACIAL, 22, "Excited upward leap expression"),
        ModularActionDef("expr_kick", "Kick Expression", ModularActionTrack.FACIAL, 22, "Intense martial kick shout"),
        ModularActionDef("expr_laugh", "Laugh Face Expression", ModularActionTrack.FACIAL, 24, "Open-mouth hearty laugh bounce"),
        ModularActionDef("expr_lipsing_with_hand2", "Lipsing With Hand2 Expression", ModularActionTrack.FACIAL, 24, "Animated storytelling dialogue"),
        ModularActionDef("expr_listener", "Listener Face Expression", ModularActionTrack.FACIAL, 28, "Attentive subtle agreement nods"),
        ModularActionDef("expr_look_around", "Lookaround Expression", ModularActionTrack.FACIAL, 34, "Scanning left-to-right gaze"),
        ModularActionDef("expr_look_behind", "Look Behind Expression", ModularActionTrack.FACIAL, 28, "Over-shoulder glance back"),
        ModularActionDef("expr_look_down", "Look Down Expression", ModularActionTrack.FACIAL, 26, "Downward gaze & head bow"),
        ModularActionDef("expr_look_left", "Look Left Expression", ModularActionTrack.FACIAL, 26, "Eyes & head turned left"),
        ModularActionDef("expr_look_right", "Look Right Expression", ModularActionTrack.FACIAL, 26, "Eyes & head turned right"),
        ModularActionDef("expr_look_up", "Look Up Expression", ModularActionTrack.FACIAL, 26, "Upward skyward gaze & chin lift"),
        ModularActionDef("expr_drinking_tea", "Sitting And Drinking Tea Expression", ModularActionTrack.FACIAL, 28, "Sipping hot chai with content sigh"),
        ModularActionDef("expr_take_water_glass_drink", "Take Water Glass Drink Expression", ModularActionTrack.FACIAL, 26, "Refreshing water sip gaze"),
        ModularActionDef("expr_talking_on_phone", "Talking On Phone Expression", ModularActionTrack.FACIAL, 26, "Animated phone call chat"),
        ModularActionDef("expr_walking_slowly", "Walking Slowly Expression", ModularActionTrack.FACIAL, 32, "Calm strolling gaze & gentle bob"),
        // Backward-compatible legacy facial IDs
        ModularActionDef("act_face_smile", "Warm Smile", ModularActionTrack.FACIAL, 24, "Happy smile + gentle nod"),
        ModularActionDef("act_face_gasp", "Shock Gasp", ModularActionTrack.FACIAL, 22, "Wide eyes + shock O mouth"),
        ModularActionDef("act_face_angry", "Fierce Glare", ModularActionTrack.FACIAL, 24, "Furrowed brows + gritted teeth"),
        ModularActionDef("act_face_laugh", "Hearty Laugh", ModularActionTrack.FACIAL, 26, "Bouncing laugh rhythm"),
        ModularActionDef("act_face_wink", "Playful Wink", ModularActionTrack.FACIAL, 24, "Asymmetric right wink + smirk"),
        ModularActionDef("act_face_talk", "Talk Cadence", ModularActionTrack.FACIAL, 28, "Natural AA / EE syllable cycle"),
        ModularActionDef("act_face_sad", "Sad Sigh", ModularActionTrack.FACIAL, 28, "Drooping gaze + soft head drop"),
        ModularActionDef("act_face_blink", "Double Blink", ModularActionTrack.FACIAL, 22, "Crisp double eye blink"),

        // ═══════════════════════════════════════════════════════════════
        // HAND PARTS (88 presets: 80 Desi/Story + 8 legacy act_hand_*)
        // ═══════════════════════════════════════════════════════════════
        ModularActionDef("hand_angry_fight", "Angry Fight Hand Part", ModularActionTrack.HANDS, 20, "Raised boxer guard & jab punches"),
        ModularActionDef("hand_angry", "Angry Hand Part", ModularActionTrack.HANDS, 24, "Shaking clenched fists at sides"),
        ModularActionDef("hand_angry_r", "Angry R Hand Part", ModularActionTrack.HANDS, 22, "Single right angry fist shake"),
        ModularActionDef("hand_bail_gadi", "Bail Gadi Hand Part", ModularActionTrack.HANDS, 26, "Holding & snapping bullock cart reins"),
        ModularActionDef("hand_boat_drive", "Boat Drive Stand Hand Part", ModularActionTrack.HANDS, 28, "Rhythmic oar rowing sweep"),
        ModularActionDef("hand_bored", "Bored Hand Part", ModularActionTrack.HANDS, 30, "Elbows-out bored akimbo rest"),
        ModularActionDef("hand_bye_bye", "Bye Bye Hand Part", ModularActionTrack.HANDS, 24, "Cheerful side-to-side farewell wave"),
        ModularActionDef("hand_carefree", "Carefree Hand Part", ModularActionTrack.HANDS, 28, "Relaxed carefree akimbo sway"),
        ModularActionDef("hand_carrying_baby", "Carrying Baby Hand Part", ModularActionTrack.HANDS, 30, "Cradling & rocking baby gently"),
        ModularActionDef("hand_carrying_bori", "Carrying Bori Hand Part", ModularActionTrack.HANDS, 26, "Supporting grain sack on shoulder"),
        ModularActionDef("hand_carrying_heavy_bori", "Carrying Heavy Bori Hand Part", ModularActionTrack.HANDS, 28, "Two-hand heavy sack shoulder grip"),
        ModularActionDef("hand_carrying_water_matka2", "Carrying Water Matka2 Hand Part", ModularActionTrack.HANDS, 28, "Side-hip & overhead matka balance"),
        ModularActionDef("hand_carrying_water_matka", "Carrying Water Matka Hand Part", ModularActionTrack.HANDS, 28, "Balancing earthen pot on head/hip"),
        ModularActionDef("hand_car_drive", "Car Drive Hand Part", ModularActionTrack.HANDS, 26, "Turning car steering wheel"),
        ModularActionDef("hand_clapping", "Clapping Hand Part", ModularActionTrack.HANDS, 22, "Rhythmic front applause claps"),
        ModularActionDef("hand_cleaning_field", "Cleaning Field Hand Part", ModularActionTrack.HANDS, 26, "Sweeping broom / sickle motion"),
        ModularActionDef("hand_combing_head", "Combing Head Hand Part", ModularActionTrack.HANDS, 24, "Brushing hair strokes with comb"),
        ModularActionDef("hand_cooking", "Cooking Hand Part", ModularActionTrack.HANDS, 26, "Stirring pot ladle steadily"),
        ModularActionDef("hand_coughing_l", "Coughing L Hand Part", ModularActionTrack.HANDS, 22, "Left hand covering mouth cough"),
        ModularActionDef("hand_coughing_r", "Coughing R Hand Part", ModularActionTrack.HANDS, 22, "Right hand covering mouth cough"),
        ModularActionDef("hand_crossed_arms", "Crossed Arms Hand Part", ModularActionTrack.HANDS, 30, "Confident folded chest arms"),
        ModularActionDef("hand_cutting_tree", "Cutting Tree Hand Part", ModularActionTrack.HANDS, 22, "Two-hand axe chopping swing"),
        ModularActionDef("hand_door_open_slowly", "Door Open Slowly Hand Part", ModularActionTrack.HANDS, 30, "Cautious reaching & pushing door"),
        ModularActionDef("hand_doubt_l", "Doubt L Hand Part", ModularActionTrack.HANDS, 26, "Left hand scratching chin in doubt"),
        ModularActionDef("hand_doubt_r", "Doubt R Hand Part", ModularActionTrack.HANDS, 26, "Right hand scratching chin in doubt"),
        ModularActionDef("hand_drinking_water", "Drink Water From Glass Hand Part", ModularActionTrack.HANDS, 26, "Tilting water glass to lips"),
        ModularActionDef("hand_bus_drive", "Driving Bus Hand Part", ModularActionTrack.HANDS, 28, "Wide bus wheel & steering"),
        ModularActionDef("hand_eating", "Eating Burger Hand Part", ModularActionTrack.HANDS, 24, "Two-hand burger bite cycle"),
        ModularActionDef("hand_fear", "Fear Hand Part", ModularActionTrack.HANDS, 22, "Trembling defensive hands raised"),
        ModularActionDef("hand_fed_up", "Fed Up Hand Part", ModularActionTrack.HANDS, 26, "Exasperated palms-up shrug"),
        ModularActionDef("hand_gaay_bhens_charana", "Gaay Bhens Charana Hand Part", ModularActionTrack.HANDS, 26, "Herding cattle stick & call"),
        ModularActionDef("hand_giving_things", "Giving Things Hand Part", ModularActionTrack.HANDS, 24, "Extending hands forward politely"),
        ModularActionDef("hand_gods_ashirwaad", "Gods Ashirwaad Hand Part", ModularActionTrack.HANDS, 28, "Raised palm divine blessing"),
        ModularActionDef("hand_gods_ashirwaad_r", "Gods Ashirwaad R Hand Part", ModularActionTrack.HANDS, 28, "Single right palm blessing"),
        ModularActionDef("hand_gun_fire", "Gun Fire Hand Part", ModularActionTrack.HANDS, 20, "Aimed arm recoil pulse"),
        ModularActionDef("hand_hal_chalana_with_bail", "Hal Chalana with Bail Hand Part", ModularActionTrack.HANDS, 26, "Guiding plough behind oxen"),
        ModularActionDef("hand_hammering", "Hammering Hand Part", ModularActionTrack.HANDS, 20, "Power overhead hammer strikes"),
        ModularActionDef("hand_hammer_nail", "Hammer Nail Hand Part", ModularActionTrack.HANDS, 20, "Steady nail-hold & rapid taps"),
        ModularActionDef("hand_covering_face", "Hands Covering Face Hand Part", ModularActionTrack.HANDS, 28, "Both palms shielding eyes/face"),
        ModularActionDef("hand_on_hips", "Hands on Hips Hand Part", ModularActionTrack.HANDS, 28, "Bold elbows-out akimbo stance"),
        ModularActionDef("hand_on_forehead", "Hand On Forehead Hand Part", ModularActionTrack.HANDS, 26, "Classic dramatic forehead slap"),
        ModularActionDef("hand_hiding", "Hiding Hand Part", ModularActionTrack.HANDS, 28, "Tucked elbows peeking behind cover"),
        ModularActionDef("hand_hit_on_head", "Hit On Head Hand Part", ModularActionTrack.HANDS, 22, "Playful bonk / head tap"),
        ModularActionDef("hand_holding_object_on_head", "Holding Object On Head Hand Part", ModularActionTrack.HANDS, 28, "Both hands steadying head load"),
        ModularActionDef("hand_holding_things", "Holding Things Hand Part", ModularActionTrack.HANDS, 26, "Carrying parcel steadily in front"),
        ModularActionDef("hand_holding_lantern", "Hold Lantern Search Hand Part", ModularActionTrack.HANDS, 28, "Raised lantern searching ahead"),
        ModularActionDef("hand_holding_plate", "Hold Plate Eating Hand Part", ModularActionTrack.HANDS, 26, "Holding thali & eating rhythm"),
        ModularActionDef("hand_hugging", "Hugging Hand Part", ModularActionTrack.HANDS, 26, "Warm wide embrace wrap"),
        ModularActionDef("hand_idea_l", "Idea L Hand Part", ModularActionTrack.HANDS, 24, "Left index finger raised eureka"),
        ModularActionDef("hand_idea_r", "Idea R Hand Part", ModularActionTrack.HANDS, 24, "Right index finger raised eureka"),
        ModularActionDef("hand_jogging", "Jogging Hand Part", ModularActionTrack.HANDS, 20, "Compact bent-elbow jogging pump"),
        ModularActionDef("hand_jump_down", "Jump Down Hand Part", ModularActionTrack.HANDS, 22, "Landing balance arm brace"),
        ModularActionDef("hand_jump", "Jump Hand Part", ModularActionTrack.HANDS, 22, "Upward launch & celebratory arms"),
        ModularActionDef("hand_jump_up", "Jump Up Hand Part", ModularActionTrack.HANDS, 22, "Explosive upward jump arm swing"),
        ModularActionDef("hand_kick", "Kick Hand Part", ModularActionTrack.HANDS, 22, "Martial kick balance guard"),
        ModularActionDef("hand_laugh", "Laugh Hand Part", ModularActionTrack.HANDS, 24, "Belly-hold laughing shakes"),
        ModularActionDef("hand_leaning_using_phone", "Leaning on Wall Using Phone Hand Part", ModularActionTrack.HANDS, 28, "Scrolling smartphone casually"),
        ModularActionDef("hand_lipsing_with_hand2", "Lipsing With Hand2 Hand Part", ModularActionTrack.HANDS, 26, "Expressive two-hand narration"),
        ModularActionDef("hand_lipsync_with_hand", "Lipsing With Hand Hand Part", ModularActionTrack.HANDS, 26, "Expressive storytelling gesture"),
        ModularActionDef("hand_listener", "Listener Hand Part", ModularActionTrack.HANDS, 28, "Polite resting akimbo/clasped"),
        ModularActionDef("hand_look_behind_talk", "Look Behind Talk Hand Part", ModularActionTrack.HANDS, 26, "Gesturing back over shoulder"),
        ModularActionDef("hand_making_roti", "Making Roti Hand Part", ModularActionTrack.HANDS, 22, "Rolling belan back-and-forth"),
        ModularActionDef("hand_namaskar", "Namaskar Hand Part", ModularActionTrack.HANDS, 28, "Folded palms respectful greeting"),
        ModularActionDef("hand_salute2", "Salute2 Hand Part", ModularActionTrack.HANDS, 26, "Respectful two-arm formal salute"),
        ModularActionDef("hand_salute", "Salute Hand Part", ModularActionTrack.HANDS, 26, "Crisp right-hand forehead salute"),
        ModularActionDef("hand_drinking_tea", "Sitting And Drinking Tea Hand Part", ModularActionTrack.HANDS, 28, "Raising chai cup to lips"),
        ModularActionDef("hand_sneak_walk", "Sneak Walk Hand Part", ModularActionTrack.HANDS, 28, "Tiptoe balanced stealth arms"),
        ModularActionDef("hand_take_water_glass_drink", "Take Water Glass Drink Hand Part", ModularActionTrack.HANDS, 26, "Lifting & sipping water glass"),
        ModularActionDef("hand_talking", "Talking Hand Part", ModularActionTrack.HANDS, 24, "Natural conversational hand beat"),
        ModularActionDef("hand_talking_on_mobile", "Talking On Mobile Hand Part", ModularActionTrack.HANDS, 26, "Holding mobile at ear"),
        ModularActionDef("hand_talking_on_phone", "Talking On Phone Hand Part", ModularActionTrack.HANDS, 26, "Holding phone & gesturing"),
        ModularActionDef("hand_walking_slowly", "Walking Slowly Hand Part", ModularActionTrack.HANDS, 32, "Unhurried gentle arm swing"),
        ModularActionDef("hand_walk_with_bori", "Walking With Bori Hand Part", ModularActionTrack.HANDS, 26, "Walking with shoulder grain bag"),
        ModularActionDef("hand_walk", "Walk Hand Part", ModularActionTrack.HANDS, 26, "Classic natural walk arm swing"),
        ModularActionDef("hand_walk_with_axe", "Walk With Axe Hand Part", ModularActionTrack.HANDS, 26, "Axe resting across shoulder"),
        ModularActionDef("hand_walk_with_carrying_things", "Walk With Carrying Things Hand Part", ModularActionTrack.HANDS, 26, "Carrying basket while walking"),
        ModularActionDef("hand_walk_with_hal", "Walk With Hal Hand Part", ModularActionTrack.HANDS, 28, "Farmer carrying wooden plough"),
        ModularActionDef("hand_walk_with_mashaal", "Walk With Mashaal Hand Part", ModularActionTrack.HANDS, 26, "Holding blazing fire torch high"),
        ModularActionDef("hand_walk_with_stick", "Walk With Stick Hand Part", ModularActionTrack.HANDS, 26, "Walking cane / lathi support"),
        ModularActionDef("hand_walk_with_sword", "Walk With Sword Hand Part", ModularActionTrack.HANDS, 24, "Warrior sword carried ready"),
        ModularActionDef("hand_walk_with_thela", "Walk With Thela Hand Part", ModularActionTrack.HANDS, 26, "Pushing street handcart handles"),
        ModularActionDef("hand_walk_with_torch", "Walk With Torch Hand Part", ModularActionTrack.HANDS, 26, "Shining flashlight beam forward"),
        // Backward-compatible legacy hand IDs
        ModularActionDef("act_hand_wave", "Friendly Wave", ModularActionTrack.HANDS, 26, "High right-hand wave arc"),
        ModularActionDef("act_hand_point", "Point Forward", ModularActionTrack.HANDS, 22, "Confident extended point"),
        ModularActionDef("act_hand_peace", "Victory Peace", ModularActionTrack.HANDS, 24, "Raised peace sign pose"),
        ModularActionDef("act_hand_thumbs", "Thumbs Up", ModularActionTrack.HANDS, 24, "Heroic thumbs-up lift"),
        ModularActionDef("act_hand_fist", "Fist Pump", ModularActionTrack.HANDS, 24, "Energetic double fist pump"),
        ModularActionDef("act_hand_present", "Open Present", ModularActionTrack.HANDS, 26, "Wide welcoming arms sweep"),
        ModularActionDef("act_hand_think", "Chin Think", ModularActionTrack.HANDS, 28, "Right hand raised to chin"),
        ModularActionDef("act_hand_clap", "Clap Beat", ModularActionTrack.HANDS, 22, "Rhythmic two-hand clap"),

        // ═══════════════════════════════════════════════════════════════
        // LEG PARTS (39 presets: 31 Desi/Story + 8 legacy act_leg_*)
        // ═══════════════════════════════════════════════════════════════
        ModularActionDef("leg_angry_fight", "Angry Fight Leg Part", ModularActionTrack.LEGS, 20, "Wide bouncy fighter footwork"),
        ModularActionDef("leg_angry", "Angry Leg Part", ModularActionTrack.LEGS, 24, "Heavy stomping rooted stance"),
        ModularActionDef("leg_bail_gadi", "Bail Gadi Leg Part", ModularActionTrack.LEGS, 26, "Seated bouncing cart rider legs"),
        ModularActionDef("leg_boat_drive", "Boat Drive Stand Leg Part", ModularActionTrack.LEGS, 28, "Braced standing boat rowing legs"),
        ModularActionDef("leg_bored", "Bored Leg Part", ModularActionTrack.LEGS, 30, "Weight-shifted lazy foot tap"),
        ModularActionDef("leg_bye_bye", "Bye Bye Leg Part", ModularActionTrack.LEGS, 26, "Light cheerful idle sway"),
        ModularActionDef("leg_carefree", "Carefree Leg Part", ModularActionTrack.LEGS, 26, "Bouncy carefree stride"),
        ModularActionDef("leg_carrying_heavy_bori", "Carrying Heavy Bori Leg Part", ModularActionTrack.LEGS, 28, "Heavy bent-knee strained walk"),
        ModularActionDef("leg_car_drive", "Car Drive Leg Part", ModularActionTrack.LEGS, 26, "Seated driver pedal stance"),
        ModularActionDef("leg_cleaning_field", "Cleaning Field Leg Part", ModularActionTrack.LEGS, 26, "Wide bent farming stance"),
        ModularActionDef("leg_cutting_tree", "Cutting Tree Leg Part", ModularActionTrack.LEGS, 24, "Braced lunge chop footing"),
        ModularActionDef("leg_door_open_slowly", "Door Open Slowly Leg Part", ModularActionTrack.LEGS, 30, "Cautious slow forward step"),
        ModularActionDef("leg_gods_ashirwaad", "Gods Ashirwaad Leg Part", ModularActionTrack.LEGS, 30, "Steady grounded blessing stance"),
        ModularActionDef("leg_hiding", "Hiding Leg Part", ModularActionTrack.LEGS, 28, "Low crouched stealth tuck"),
        ModularActionDef("leg_hold_lantern_search", "Hold Lantern Search Leg Part", ModularActionTrack.LEGS, 28, "Careful probing night walk"),
        ModularActionDef("leg_jogging", "Jogging Leg Part", ModularActionTrack.LEGS, 20, "Energetic medium jogging cycle"),
        ModularActionDef("leg_jump_down", "Jump Down Leg Part", ModularActionTrack.LEGS, 22, "Air drop -> deep knee cushion"),
        ModularActionDef("leg_jump", "Jump Leg Part", ModularActionTrack.LEGS, 22, "Spring crouch -> vertical leap"),
        ModularActionDef("leg_jump_up", "Jump Up Leg Part", ModularActionTrack.LEGS, 22, "Crouch -> explosive upward leap"),
        ModularActionDef("leg_kicking_ball", "Kicking Ball Leg Part", ModularActionTrack.LEGS, 22, "Backswing -> football strike"),
        ModularActionDef("leg_kick", "Kick Leg Part", ModularActionTrack.LEGS, 22, "High martial snap kick"),
        ModularActionDef("leg_leaning_on_wall", "Leaning on Wall Using Phone Leg Part", ModularActionTrack.LEGS, 30, "Relaxed figure-4 wall lean"),
        ModularActionDef("leg_listener", "Listener Leg Part", ModularActionTrack.LEGS, 28, "Calm balanced standing pose"),
        ModularActionDef("leg_namaskar_sitting", "Namaskar Sitting Leg Part", ModularActionTrack.LEGS, 30, "Cross-legged respectful bow"),
        ModularActionDef("leg_salute2", "Salute2 Leg Part", ModularActionTrack.LEGS, 28, "Formal parade attention stance"),
        ModularActionDef("leg_salute", "Salute Leg Part", ModularActionTrack.LEGS, 28, "Crisp heels-together attention"),
        ModularActionDef("leg_sitting_floor_idle", "Sitting Floor Idle Leg Part", ModularActionTrack.LEGS, 30, "Cross-legged palathi floor sit"),
        ModularActionDef("leg_sitting", "Sitting Leg Part", ModularActionTrack.LEGS, 28, "Seated chair thighs & calves"),
        ModularActionDef("leg_sneak_walk", "Sneak Walk Leg Part", ModularActionTrack.LEGS, 28, "Low tiptoe stealth creeping"),
        ModularActionDef("leg_walking_slowly", "Walking Slowly Leg Part", ModularActionTrack.LEGS, 32, "Unhurried gentle strolling pace"),
        ModularActionDef("leg_walk", "Walk Leg Part", ModularActionTrack.LEGS, 26, "Classic balanced walk cycle"),
        // Backward-compatible legacy leg IDs
        ModularActionDef("act_leg_walk", "Walk Stride", ModularActionTrack.LEGS, 26, "Smooth alternating stride"),
        ModularActionDef("act_leg_sprint", "Sprint Cycle", ModularActionTrack.LEGS, 18, "Fast high-knee run swing"),
        ModularActionDef("act_leg_jump", "Hero Jump", ModularActionTrack.LEGS, 24, "Crouch -> launch -> tuck -> land"),
        ModularActionDef("act_leg_sit", "Sit Relaxed", ModularActionTrack.LEGS, 28, "Bent thighs + grounded calves"),
        ModularActionDef("act_leg_kick", "Kick Strike", ModularActionTrack.LEGS, 22, "Chamber -> snap kick -> recover"),
        ModularActionDef("act_leg_sneak", "Tip-Toe Sneak", ModularActionTrack.LEGS, 28, "Cautious bent-knee step"),
        ModularActionDef("act_leg_squat", "Squat Bounce", ModularActionTrack.LEGS, 24, "Deep knee bend & spring"),
        ModularActionDef("act_leg_stance", "Wide Stance", ModularActionTrack.LEGS, 24, "Braced hero power stance")
    )

    /**
     * Smooth C1-continuous pulse (0 at ends, 1 at peak) with zero derivative discontinuity.
     */
    private fun smoothPulse(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return (0.5f - 0.5f * cos(x * 2.0 * PI)).toFloat()
    }

    fun evaluate(action: ModularActionDef, progress: Float): ModularActionSample {
        val p = ((progress % 1f) + 1f) % 1f
        val rad = p * 2.0 * PI
        val wave = sin(rad).toFloat()
        val waveLag = sin(rad - 0.45).toFloat()        // realistic follow-through phase lag!
        val wave2 = sin(rad * 2.0).toFloat()
        val wave3 = sin(rad * 3.0).toFloat()
        val cosW = cos(rad).toFloat()
        val cos2 = cos(rad * 2.0).toFloat()
        val pulse = smoothPulse(p)                     // 0 -> 1 -> 0 smoothly
        val bounce = (0.5f - 0.5f * cos2)              // 0 -> 1 -> 0 -> 1 -> 0 double-beat gait bounce
        val hint = action.propHint

        return when (action.id) {
            // ═══════════════════════════════════════════════════════════════
            // EXPRESSIONS (seamless periodic facial animation)
            // ═══════════════════════════════════════════════════════════════
            "expr_angry_face", "act_face_angry" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                facialPresetId = FacialExpressionEngine.PRESET_ANGRY,
                mouthShapeOverride = MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = 5f + 2.5f * wave2,
                gazeY = -0.10f
            )
            "expr_angry_fight" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                facialPresetId = FacialExpressionEngine.PRESET_ANGRY,
                mouthShapeOverride = if (wave2 > 0.15f) MouthShapeOverride.TALK_AA else MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = 7f + 5f * wave2
            )
            "expr_angry_lipsync" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                facialPresetId = FacialExpressionEngine.PRESET_ANGRY,
                mouthShapeOverride = when {
                    wave3 > 0.35f -> MouthShapeOverride.TALK_AA
                    wave3 > -0.20f -> MouthShapeOverride.SHOCK_O
                    else -> MouthShapeOverride.GRIT_ANGRY
                },
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = 4f + 4.5f * wave2
            )
            "expr_boat_drive_stand", "expr_cleaning_field" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.NEUTRAL,
                headNodDelta = 4f + 4f * wave,
                gazeX = 0.25f,
                gazeY = 0.15f
            )
            "expr_bored" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SAD,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.SAD_DROOP,
                eyebrowShapeOverride = EyebrowShapeOverride.NEUTRAL,
                headNodDelta = 8f + 2.5f * wave,
                gazeX = -0.35f + 0.12f * wave,
                gazeY = 0.30f
            )
            "expr_carefree" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -4f * wave,
                gazeX = 0.28f * wave
            )
            "expr_carrying_heavy_bori" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                mouthShapeOverride = MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = 9f + 3f * bounce
            )
            "expr_clapping" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = MouthShapeOverride.OPEN_LAUGH,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -6f * bounce
            )
            "expr_combing_head" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMIRK,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -3f + 4f * wave,
                gazeX = 0.22f
            )
            "expr_coughing" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                mouthShapeOverride = if (bounce > 0.45f) MouthShapeOverride.SHOCK_O else MouthShapeOverride.FROWN,
                eyeShapeOverride = if (bounce > 0.45f) EyeShapeOverride.CLOSED_SLEEP else EyeShapeOverride.SAD_DROOP,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = 15f * bounce
            )
            "expr_door_open_slowly" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                gazeX = 0.55f + 0.15f * wave,
                headNodDelta = 3f * wave
            )
            "expr_doubt" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                facialPresetId = FacialExpressionEngine.PRESET_SUSPICIOUS,
                mouthShapeOverride = MouthShapeOverride.SMIRK,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.SKEPTICAL_ONE_UP,
                headNodDelta = 7f + 2f * wave,
                gazeX = 0.58f,
                gazeY = -0.22f
            )
            "expr_eating", "expr_hold_plate_eating" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                mouthShapeOverride = if (wave2 > 0.05f) MouthShapeOverride.TALK_AA else MouthShapeOverride.SMILE,
                eyeShapeOverride = if (wave2 < -0.55f) EyeShapeOverride.HAPPY_ARC else EyeShapeOverride.NORMAL,
                headNodDelta = 3.5f * wave2,
                gazeY = 0.20f
            )
            "expr_fear" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                facialPresetId = FacialExpressionEngine.PRESET_SHOCKED,
                mouthShapeOverride = MouthShapeOverride.SHOCK_O,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = -3f + 2.5f * wave3,
                gazeX = 0.30f * wave2
            )
            "expr_fed_up" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                mouthShapeOverride = MouthShapeOverride.FROWN,
                eyeShapeOverride = EyeShapeOverride.SAD_DROOP,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = -5f - 3f * pulse,
                gazeY = -0.68f
            )
            "expr_gods_ashirwaad" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = 3f + 2.5f * wave
            )
            "expr_hammering", "expr_hammer_nail" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                mouthShapeOverride = MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = 3f + 8f * bounce,
                gazeY = 0.30f
            )
            "expr_hand_on_forehead" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SAD,
                mouthShapeOverride = MouthShapeOverride.FROWN,
                eyeShapeOverride = EyeShapeOverride.CLOSED_SLEEP,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                blinkOverride = true,
                headNodDelta = 12f + 2.5f * wave
            )
            "expr_hiding" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = 5f,
                gazeX = 0.78f * wave
            )
            "expr_hold_lantern_search" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                gazeX = 0.65f * wave,
                gazeY = -0.15f,
                headNodDelta = 3f * cosW
            )
            "expr_idea" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = if (pulse > 0.35f) MouthShapeOverride.OPEN_LAUGH else MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                headNodDelta = -8f * pulse,
                gazeY = -0.35f
            )
            "expr_idle_happy" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                blinkOverride = p in 0.78f..0.88f,
                headNodDelta = 2.2f * wave
            )
            "expr_idle_sad" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SAD,
                facialPresetId = FacialExpressionEngine.PRESET_SAD,
                mouthShapeOverride = MouthShapeOverride.FROWN,
                eyeShapeOverride = EyeShapeOverride.SAD_DROOP,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = 9f + 2f * wave,
                gazeY = 0.45f
            )
            "expr_idle_angry" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                facialPresetId = FacialExpressionEngine.PRESET_ANGRY,
                mouthShapeOverride = MouthShapeOverride.FROWN,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = 4.5f + 1.5f * wave
            )
            "expr_jogging" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = if (bounce > 0.5f) MouthShapeOverride.TALK_EE else MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.NEUTRAL,
                headNodDelta = 3f * bounce,
                bodyBobDelta = -5f * bounce
            )
            "expr_kick" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                mouthShapeOverride = if (pulse > 0.4f) MouthShapeOverride.TALK_AA else MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = -6f * pulse
            )
            "expr_laugh", "act_face_laugh" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.LAUGHING,
                facialPresetId = FacialExpressionEngine.PRESET_LAUGH,
                mouthShapeOverride = if (bounce > 0.30f) MouthShapeOverride.OPEN_LAUGH else MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -8f * bounce,
                bodyBobDelta = -3f * bounce
            )
            "expr_lipsing_with_hand2" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.TALKING,
                mouthShapeOverride = when {
                    wave3 > 0.40f -> MouthShapeOverride.TALK_AA
                    wave3 > -0.15f -> MouthShapeOverride.TALK_EE
                    else -> MouthShapeOverride.SMILE
                },
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = 4.5f * wave
            )
            "expr_listener" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = 7f * bounce
            )
            "expr_look_around" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                gazeX = 0.88f * wave,
                gazeY = -0.10f * cosW,
                headNodDelta = 3.5f * cosW
            )
            "expr_look_behind" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                gazeX = -0.92f,
                headNodDelta = -5f + 2f * wave,
                silhouetteStyle = "head_turn_behind"
            )
            "expr_look_left" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                gazeX = -0.92f,
                headNodDelta = 2f * wave,
                silhouetteStyle = "head_turn_left"
            )
            "expr_look_right" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                gazeX = 0.92f,
                headNodDelta = 2f * wave,
                silhouetteStyle = "head_turn_right"
            )
            "expr_look_up" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                gazeY = -0.88f,
                headNodDelta = -14f + 2f * wave
            )
            "expr_look_down" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SAD,
                gazeY = 0.88f,
                headNodDelta = 15f + 2f * wave
            )
            "expr_talking_on_phone" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.TALKING,
                mouthShapeOverride = if (wave3 > 0.1f) MouthShapeOverride.TALK_AA else MouthShapeOverride.TALK_EE,
                eyeShapeOverride = EyeShapeOverride.NORMAL,
                headNodDelta = 5f * wave
            )
            "expr_drinking_tea" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                mouthShapeOverride = if (pulse > 0.35f) MouthShapeOverride.SHOCK_O else MouthShapeOverride.SMILE,
                eyeShapeOverride = if (pulse > 0.45f) EyeShapeOverride.HAPPY_ARC else EyeShapeOverride.NORMAL,
                headNodDelta = -8f * pulse
            )
            "expr_drinking_water", "expr_take_water_glass_drink" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = if (pulse > 0.30f) MouthShapeOverride.SHOCK_O else MouthShapeOverride.SMILE,
                eyeShapeOverride = if (pulse > 0.40f) EyeShapeOverride.CLOSED_SLEEP else EyeShapeOverride.NORMAL,
                headNodDelta = -18f * pulse
            )
            "expr_walking_slowly" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.NEUTRAL,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                headNodDelta = 2.8f * wave,
                bodyBobDelta = -3f * bounce
            )
            "expr_jump_up" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                mouthShapeOverride = MouthShapeOverride.OPEN_LAUGH,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                headNodDelta = -10f * pulse,
                bodyBobDelta = -12f * pulse
            )
            "expr_jump_down" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                mouthShapeOverride = MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                headNodDelta = 10f * pulse,
                bodyBobDelta = 8f * pulse
            )
            "act_face_smile" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -4f * pulse
            )
            "act_face_gasp" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                facialPresetId = FacialExpressionEngine.PRESET_SHOCKED,
                mouthShapeOverride = MouthShapeOverride.SHOCK_O,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                headNodDelta = -8f * pulse
            )
            "act_face_wink" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_WINK,
                mouthShapeOverride = MouthShapeOverride.SMIRK,
                eyeShapeOverride = if (pulse > 0.25f) EyeShapeOverride.WINK_RIGHT else EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = 4f * pulse
            )
            "act_face_talk" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.TALKING,
                mouthShapeOverride = when {
                    wave2 > 0.45f -> MouthShapeOverride.TALK_AA
                    wave2 > -0.15f -> MouthShapeOverride.TALK_EE
                    else -> MouthShapeOverride.SMILE
                },
                headNodDelta = 4f * wave
            )
            "act_face_sad" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SAD,
                facialPresetId = FacialExpressionEngine.PRESET_SAD,
                mouthShapeOverride = MouthShapeOverride.FROWN,
                eyeShapeOverride = EyeShapeOverride.SAD_DROOP,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = 11f + 2f * wave
            )
            "act_face_blink" -> {
                val closed = p in 0.18f..0.32f || p in 0.56f..0.70f
                ModularActionSample(
                    track = ModularActionTrack.FACIAL,
                    blinkOverride = closed,
                    eyeShapeOverride = if (closed) EyeShapeOverride.CLOSED_SLEEP else EyeShapeOverride.NORMAL,
                    headNodDelta = if (closed) 2.5f else 0f
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // HAND PARTS — TRUE ANDROID CANVAS ANATOMICAL SIGNS + ZERO-SNAP LOOPS!
            // Left Arm: `+arm` = outward/up, `-forearm` = fold inward to chest/face
            // Right Arm: `-arm` = outward/up, `+forearm` = fold inward to chest/face
            // ═══════════════════════════════════════════════════════════════
            "hand_angry_fight" -> {
                val jabL = wave2.coerceAtLeast(0f)
                val jabR = (-wave2).coerceAtLeast(0f)
                ModularActionSample(
                    track = ModularActionTrack.HANDS,
                    handPoseOverride = HandPoseType.FIST,
                    leftArmDelta = 44f + 44f * jabL,
                    leftForearmDelta = -108f + 74f * jabL,
                    rightArmDelta = -44f - 44f * jabR,
                    rightForearmDelta = 108f - 74f * jabR
                )
            }
            "hand_angry" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 20f + 8f * wave3,
                leftForearmDelta = -32f - 10f * wave3,
                rightArmDelta = -20f - 8f * wave3,
                rightForearmDelta = 32f + 10f * wave3
            )
            "hand_angry_r" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                rightArmDelta = -32f - 14f * wave2,
                rightForearmDelta = 46f + 18f * wave2,
                silhouetteStyle = "single_right_arm"
            )
            "hand_bail_gadi", "hand_car_drive", "hand_bus_drive", "hand_walk_with_hal", "hand_hal_chalana_with_bail" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = -38f + 10f * wave,
                leftForearmDelta = -46f + 8f * waveLag,
                rightArmDelta = -42f - 10f * wave,
                rightForearmDelta = -42f - 8f * waveLag
            )
            "hand_boat_drive" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 36f + 22f * wave,
                leftForearmDelta = -76f + 28f * cosW,
                rightArmDelta = -36f - 22f * wave,
                rightForearmDelta = 76f - 28f * cosW
            )
            "hand_bored", "hand_carefree", "hand_on_hips", "hand_listener" -> {
                val sway = if (action.id == "hand_carefree") 7f * wave else 2.5f * wave
                ModularActionSample(
                    track = ModularActionTrack.HANDS,
                    handPoseOverride = if (action.id == "hand_on_hips") HandPoseType.FIST else HandPoseType.OPEN,
                    leftArmDelta = 42f + sway,
                    leftForearmDelta = -82f - sway * 0.6f,
                    rightArmDelta = -42f + sway,
                    rightForearmDelta = 82f - sway * 0.6f
                )
            }
            "hand_bye_bye", "act_hand_wave" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 12f + 3f * wave,
                leftForearmDelta = -10f,
                rightArmDelta = -126f + 10f * wave,
                rightForearmDelta = 26f + 32f * wave2
            )
            "hand_carrying_baby" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 24f + 6f * wave,
                leftForearmDelta = -88f + 5f * wave,
                rightArmDelta = -36f + 6f * wave,
                rightForearmDelta = 96f + 5f * wave
            )
            "hand_carrying_bori", "hand_walk_with_bori" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 22f * wave,
                leftForearmDelta = -16f - 8f * waveLag,
                rightArmDelta = -76f + 5f * wave,
                rightForearmDelta = 112f - 4f * wave
            )
            "hand_carrying_heavy_bori" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 68f + 5f * bounce,
                leftForearmDelta = -114f - 4f * bounce,
                rightArmDelta = -68f - 5f * bounce,
                rightForearmDelta = 114f + 4f * bounce
            )
            "hand_carrying_water_matka", "hand_carrying_water_matka2" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 38f + 4f * wave,
                leftForearmDelta = -76f,
                rightArmDelta = -118f + 4f * wave,
                rightForearmDelta = 96f - 4f * wave
            )
            "hand_clapping", "act_hand_clap" -> {
                val clap = bounce
                ModularActionSample(
                    track = ModularActionTrack.HANDS,
                    handPoseOverride = HandPoseType.OPEN,
                    leftArmDelta = 38f + 20f * (1f - clap),
                    leftForearmDelta = -68f - 42f * clap,
                    rightArmDelta = -38f - 20f * (1f - clap),
                    rightForearmDelta = 68f + 42f * clap
                )
            }
            "hand_cleaning_field" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = -22f + 26f * wave,
                leftForearmDelta = -40f + 18f * waveLag,
                rightArmDelta = -48f + 26f * wave,
                rightForearmDelta = 24f + 16f * waveLag
            )
            "hand_combing_head" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 34f,
                leftForearmDelta = -72f,
                rightArmDelta = -114f + 12f * wave2,
                rightForearmDelta = 112f - 24f * wave2
            )
            "hand_cooking" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 24f,
                leftForearmDelta = -58f,
                rightArmDelta = -48f + 14f * wave2,
                rightForearmDelta = 64f + 22f * cosW
            )
            "hand_coughing_l" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 52f + 8f * bounce,
                leftForearmDelta = -124f - 6f * bounce,
                rightArmDelta = -12f,
                rightForearmDelta = 16f
            )
            "hand_coughing_r" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 12f,
                leftForearmDelta = -16f,
                rightArmDelta = -52f - 8f * bounce,
                rightForearmDelta = 124f + 6f * bounce
            )
            "hand_crossed_arms" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 28f + 2f * wave,
                leftForearmDelta = -110f,
                rightArmDelta = -28f - 2f * wave,
                rightForearmDelta = 110f
            )
            "hand_cutting_tree" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 42f + 66f * wave,
                leftForearmDelta = -64f + 24f * waveLag,
                rightArmDelta = -52f - 64f * wave,
                rightForearmDelta = 68f - 24f * waveLag
            )
            "hand_door_open_slowly" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 12f,
                leftForearmDelta = -14f,
                rightArmDelta = -48f - 32f * pulse,
                rightForearmDelta = 46f - 28f * pulse
            )
            "hand_doubt_l" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 52f,
                leftForearmDelta = -122f - 6f * wave2,
                rightArmDelta = -18f,
                rightForearmDelta = 26f
            )
            "hand_doubt_r", "act_hand_think" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 18f,
                leftForearmDelta = -26f,
                rightArmDelta = -52f,
                rightForearmDelta = 122f + 6f * wave2
            )
            "hand_drinking_tea" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 16f,
                leftForearmDelta = -32f,
                rightArmDelta = -38f - 26f * pulse,
                rightForearmDelta = 72f + 48f * pulse
            )
            "hand_drinking_water", "hand_take_water_glass_drink" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 14f,
                leftForearmDelta = -20f,
                rightArmDelta = -44f - 42f * pulse,
                rightForearmDelta = 76f + 46f * pulse
            )
            "hand_eating" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 42f + 10f * bounce,
                leftForearmDelta = -104f - 14f * bounce,
                rightArmDelta = -42f - 10f * bounce,
                rightForearmDelta = 104f + 14f * bounce
            )
            "hand_fear" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 48f + 5f * wave3,
                leftForearmDelta = -102f - 6f * wave3,
                rightArmDelta = -48f - 5f * wave3,
                rightForearmDelta = 102f + 6f * wave3
            )
            "hand_fed_up" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 52f + 12f * pulse,
                leftForearmDelta = -44f - 12f * pulse,
                rightArmDelta = -52f - 12f * pulse,
                rightForearmDelta = 44f + 12f * pulse
            )
            "hand_gaay_bhens_charana" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 48f + 14f * wave,
                leftForearmDelta = -76f + 12f * wave,
                rightArmDelta = -56f - 18f * wave,
                rightForearmDelta = 82f - 16f * wave
            )
            "hand_giving_things", "hand_holding_things" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 34f + 14f * pulse,
                leftForearmDelta = -62f + 16f * pulse,
                rightArmDelta = -44f - 18f * pulse,
                rightForearmDelta = 58f - 14f * pulse
            )
            "hand_gods_ashirwaad" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 18f,
                leftForearmDelta = -34f,
                rightArmDelta = -88f - 6f * pulse,
                rightForearmDelta = 48f + 6f * wave
            )
            "hand_gods_ashirwaad_r" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                rightArmDelta = -92f - 6f * pulse,
                rightForearmDelta = 44f + 6f * wave,
                silhouetteStyle = "single_right_arm"
            )
            "hand_gun_fire" -> {
                val recoil = (wave2 * wave2)
                ModularActionSample(
                    track = ModularActionTrack.HANDS,
                    handPoseOverride = HandPoseType.POINT,
                    leftArmDelta = 16f,
                    leftForearmDelta = -22f,
                    rightArmDelta = -84f - 14f * recoil,
                    rightForearmDelta = 12f + 10f * recoil
                )
            }
            "hand_hammering" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 28f,
                leftForearmDelta = -48f,
                rightArmDelta = -72f - 54f * wave2,
                rightForearmDelta = 58f + 32f * wave2
            )
            "hand_hammer_nail" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 40f,
                leftForearmDelta = -84f,
                rightArmDelta = -54f - 36f * wave2,
                rightForearmDelta = 88f - 28f * wave2
            )
            "hand_covering_face" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 62f + 3f * wave,
                leftForearmDelta = -134f,
                rightArmDelta = -62f - 3f * wave,
                rightForearmDelta = 134f
            )
            "hand_on_forehead" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 14f,
                leftForearmDelta = -24f,
                rightArmDelta = -82f - 4f * wave,
                rightForearmDelta = 134f
            )
            "hand_hiding" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 46f + 4f * wave,
                leftForearmDelta = -104f,
                rightArmDelta = -46f - 4f * wave,
                rightForearmDelta = 104f
            )
            "hand_hit_on_head" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 18f,
                leftForearmDelta = -28f,
                rightArmDelta = -112f - 22f * wave2,
                rightForearmDelta = 94f + 26f * wave2
            )
            "hand_holding_object_on_head" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 122f + 3f * wave,
                leftForearmDelta = -92f,
                rightArmDelta = -122f - 3f * wave,
                rightForearmDelta = 92f
            )
            "hand_holding_lantern" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 16f,
                leftForearmDelta = -22f,
                rightArmDelta = -88f + 8f * wave,
                rightForearmDelta = 24f + 8f * waveLag
            )
            "hand_holding_plate" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 38f,
                leftForearmDelta = -86f,
                rightArmDelta = -38f - 22f * bounce,
                rightForearmDelta = 82f + 36f * bounce
            )
            "hand_hugging" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 52f + 16f * pulse,
                leftForearmDelta = -72f - 36f * pulse,
                rightArmDelta = -52f - 16f * pulse,
                rightForearmDelta = 72f + 36f * pulse
            )
            "hand_idea_l" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 114f + 8f * pulse,
                leftForearmDelta = -34f,
                rightArmDelta = -16f,
                rightForearmDelta = 22f
            )
            "hand_idea_r" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 16f,
                leftForearmDelta = -22f,
                rightArmDelta = -114f - 8f * pulse,
                rightForearmDelta = 34f
            )
            "hand_jogging" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 46f * wave,
                leftForearmDelta = -84f + 12f * waveLag,
                rightArmDelta = -46f * wave,
                rightForearmDelta = 84f - 12f * waveLag
            )
            "hand_jump", "hand_jump_up" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 24f + 116f * pulse,
                leftForearmDelta = -26f + 14f * pulse,
                rightArmDelta = -24f - 116f * pulse,
                rightForearmDelta = 26f - 14f * pulse
            )
            "hand_jump_down" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 62f - 34f * pulse,
                leftForearmDelta = -28f - 18f * pulse,
                rightArmDelta = -62f + 34f * pulse,
                rightForearmDelta = 28f + 18f * pulse
            )
            "hand_kick" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 46f + 18f * pulse,
                leftForearmDelta = -72f,
                rightArmDelta = -42f - 24f * pulse,
                rightForearmDelta = 68f
            )
            "hand_laugh" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 30f + 8f * bounce,
                leftForearmDelta = -96f - 12f * bounce,
                rightArmDelta = -30f - 8f * bounce,
                rightForearmDelta = 96f + 12f * bounce
            )
            "hand_leaning_using_phone" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 36f,
                leftForearmDelta = -88f,
                rightArmDelta = -40f,
                rightForearmDelta = 96f + 8f * wave2
            )
            "hand_lipsync_with_hand", "hand_lipsing_with_hand2", "hand_talking" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 30f + 22f * wave,
                leftForearmDelta = -68f - 24f * waveLag,
                rightArmDelta = -36f - 24f * cosW,
                rightForearmDelta = 72f + 26f * wave2
            )
            "hand_look_behind_talk" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 26f,
                leftForearmDelta = -52f,
                rightArmDelta = -76f - 14f * wave,
                rightForearmDelta = 26f + 16f * wave2
            )
            "hand_making_roti" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = -26f + 18f * wave2,
                leftForearmDelta = -56f - 18f * wave2,
                rightArmDelta = -36f - 16f * wave2,
                rightForearmDelta = -46f + 18f * wave2
            )
            "hand_namaskar" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 42f + 4f * pulse,
                leftForearmDelta = -124f - 6f * pulse,
                rightArmDelta = -42f - 4f * pulse,
                rightForearmDelta = 124f + 6f * pulse
            )
            "hand_salute", "hand_salute2" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 8f,
                leftForearmDelta = -8f,
                rightArmDelta = -98f - 4f * pulse,
                rightForearmDelta = 124f
            )
            "hand_sneak_walk" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 46f + 18f * wave,
                leftForearmDelta = -78f + 16f * waveLag,
                rightArmDelta = -46f - 18f * wave,
                rightForearmDelta = 78f - 16f * waveLag
            )
            "hand_talking_on_phone", "hand_talking_on_mobile" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 26f + 16f * wave2,
                leftForearmDelta = -58f - 18f * wave2,
                rightArmDelta = -76f,
                rightForearmDelta = 132f
            )
            "hand_walk" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 30f * wave,
                leftForearmDelta = -18f + 12f * waveLag,
                rightArmDelta = -30f * wave,
                rightForearmDelta = 18f - 12f * waveLag
            )
            "hand_walking_slowly" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 18f * wave,
                leftForearmDelta = -12f + 8f * waveLag,
                rightArmDelta = -18f * wave,
                rightForearmDelta = 12f - 8f * waveLag
            )
            "hand_walk_with_axe" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 24f * wave,
                leftForearmDelta = -14f + 8f * waveLag,
                rightArmDelta = -78f + 6f * wave,
                rightForearmDelta = 104f - 5f * wave
            )
            "hand_walk_with_carrying_things" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = -26f + 6f * wave,
                leftForearmDelta = -68f,
                rightArmDelta = -32f - 6f * wave,
                rightForearmDelta = -62f
            )
            "hand_walk_with_mashaal" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 22f * wave,
                leftForearmDelta = -12f,
                rightArmDelta = -124f + 7f * wave,
                rightForearmDelta = 24f - 5f * wave
            )
            "hand_walk_with_stick" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 22f * wave,
                leftForearmDelta = -14f,
                rightArmDelta = -32f + 16f * wave,
                rightForearmDelta = 46f - 12f * waveLag
            )
            "hand_walk_with_sword" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 24f * wave,
                leftForearmDelta = -18f,
                rightArmDelta = -54f + 14f * wave,
                rightForearmDelta = 38f - 10f * waveLag
            )
            "hand_walk_with_thela" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = -44f + 6f * wave,
                leftForearmDelta = -22f + 4f * wave,
                rightArmDelta = -48f - 6f * wave,
                rightForearmDelta = -20f - 4f * wave
            )
            "hand_walk_with_torch" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 18f * wave,
                leftForearmDelta = -12f,
                rightArmDelta = -76f + 10f * wave,
                rightForearmDelta = 26f - 8f * waveLag
            )
            "act_hand_point" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 12f,
                leftForearmDelta = -12f,
                rightArmDelta = -84f - 8f * pulse,
                rightForearmDelta = 12f
            )
            "act_hand_peace" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                leftArmDelta = 14f,
                leftForearmDelta = -18f,
                rightArmDelta = -104f - 6f * pulse,
                rightForearmDelta = 52f
            )
            "act_hand_thumbs" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                leftArmDelta = 14f,
                leftForearmDelta = -16f,
                rightArmDelta = -68f - 8f * pulse,
                rightForearmDelta = 64f
            )
            "act_hand_fist" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.FIST,
                leftArmDelta = 56f + 26f * bounce,
                leftForearmDelta = -74f - 16f * bounce,
                rightArmDelta = -56f - 26f * bounce,
                rightForearmDelta = 74f + 16f * bounce
            )
            "act_hand_present" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 66f + 16f * pulse,
                leftForearmDelta = -22f + 10f * pulse,
                rightArmDelta = -66f - 16f * pulse,
                rightForearmDelta = 22f - 10f * pulse
            )

            // ═══════════════════════════════════════════════════════════════
            // LEG PARTS — SEAMLESS LOOPS + REALISTIC WEIGHT SHIFT + BODY BOB
            // ═══════════════════════════════════════════════════════════════
            "leg_angry_fight" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -26f + 10f * wave2,
                leftKneeDelta = 28f + 10f * bounce,
                rightLegDelta = 24f - 10f * wave2,
                rightKneeDelta = 24f + 10f * bounce,
                torsoTiltDelta = 8f,
                bodyBobDelta = 6f - 5f * bounce
            )
            "leg_angry" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -14f,
                leftKneeDelta = 14f,
                rightLegDelta = 12f - 24f * bounce,
                rightKneeDelta = 16f + 30f * bounce,
                torsoTiltDelta = 6f,
                bodyBobDelta = 3f * bounce
            )
            "leg_bail_gadi", "leg_car_drive" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -74f + 3f * wave2,
                leftKneeDelta = 82f - 3f * wave2,
                rightLegDelta = -66f + 5f * wave,
                rightKneeDelta = 74f - 5f * wave,
                torsoTiltDelta = 3f,
                bodyBobDelta = 18f + 2.5f * wave2,
                silhouetteStyle = "seated_profile"
            )
            "leg_boat_drive" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -12f + 4f * wave,
                leftKneeDelta = 14f,
                rightLegDelta = 12f - 4f * wave,
                rightKneeDelta = 14f,
                torsoTiltDelta = 12f * wave,
                bodyBobDelta = 2f * bounce
            )
            "leg_bored" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -10f,
                leftKneeDelta = 10f,
                rightLegDelta = 14f,
                rightKneeDelta = 12f + 10f * bounce,
                torsoTiltDelta = -4f
            )
            "leg_bye_bye", "leg_listener" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -4f + 5f * wave,
                leftKneeDelta = 6f * bounce,
                rightLegDelta = 4f - 5f * wave,
                rightKneeDelta = 6f * bounce,
                torsoTiltDelta = 2f * wave
            )
            "leg_carefree" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -32f * wave,
                leftKneeDelta = 38f * (waveLag.coerceAtLeast(0f)),
                rightLegDelta = 32f * wave,
                rightKneeDelta = 38f * ((-waveLag).coerceAtLeast(0f)),
                torsoTiltDelta = -3f * wave,
                bodyBobDelta = -7f * bounce
            )
            "leg_carrying_heavy_bori" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -16f - 18f * wave,
                leftKneeDelta = 36f + 16f * (wave.coerceAtLeast(0f)),
                rightLegDelta = -16f + 18f * wave,
                rightKneeDelta = 36f + 16f * ((-wave).coerceAtLeast(0f)),
                torsoTiltDelta = 14f,
                bodyBobDelta = 8f + 4f * bounce
            )
            "leg_cleaning_field" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -22f,
                leftKneeDelta = 32f,
                rightLegDelta = 24f,
                rightKneeDelta = 30f,
                torsoTiltDelta = 18f + 5f * wave,
                bodyBobDelta = 7f
            )
            "leg_cutting_tree" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -28f,
                leftKneeDelta = 28f,
                rightLegDelta = 28f,
                rightKneeDelta = 22f,
                torsoTiltDelta = 10f * wave,
                bodyBobDelta = 5f
            )
            "leg_door_open_slowly" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -14f * pulse,
                leftKneeDelta = 18f * pulse,
                rightLegDelta = 12f * pulse,
                rightKneeDelta = 14f * pulse,
                torsoTiltDelta = 7f * pulse
            )
            "leg_gods_ashirwaad", "leg_salute", "leg_salute2" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -3f,
                leftKneeDelta = 3f,
                rightLegDelta = 3f,
                rightKneeDelta = 3f,
                torsoTiltDelta = -1.5f
            )
            "leg_hiding" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -48f,
                leftKneeDelta = 90f,
                rightLegDelta = -44f,
                rightKneeDelta = 86f,
                torsoTiltDelta = 14f,
                bodyBobDelta = 18f + 2f * wave
            )
            "leg_hold_lantern_search" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -20f * wave,
                leftKneeDelta = 22f * (wave.coerceAtLeast(0f)) + 10f,
                rightLegDelta = 20f * wave,
                rightKneeDelta = 22f * ((-wave).coerceAtLeast(0f)) + 10f,
                torsoTiltDelta = 7f,
                bodyBobDelta = 3f - 3f * bounce
            )
            "leg_jogging" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -46f * wave,
                leftKneeDelta = 24f + 44f * (waveLag.coerceAtLeast(0f)),
                rightLegDelta = 46f * wave,
                rightKneeDelta = 24f + 44f * ((-waveLag).coerceAtLeast(0f)),
                torsoTiltDelta = 9f,
                bodyBobDelta = -11f * bounce,
                squashX = 1f + 0.03f * cos2,
                squashY = 1f - 0.03f * cos2
            )
            "leg_jump_up", "leg_jump", "act_leg_jump" -> {
                // C1-continuous anticipation crouch -> launch stretch -> apex tuck -> cushioned landing
                val crouch = if (p < 0.24f) smoothPulse(p / 0.24f) else if (p > 0.80f) smoothPulse((p - 0.80f) / 0.20f) else 0f
                val air = if (p in 0.22f..0.82f) sin(((p - 0.22f) / 0.60f) * PI).toFloat().coerceIn(0f, 1f) else 0f
                ModularActionSample(
                    track = ModularActionTrack.LEGS,
                    leftLegDelta = -34f * crouch - 24f * air,
                    leftKneeDelta = 68f * crouch + 56f * air,
                    rightLegDelta = -30f * crouch - 20f * air,
                    rightKneeDelta = 64f * crouch + 50f * air,
                    torsoTiltDelta = 10f * crouch - 5f * air,
                    bodyBobDelta = 14f * crouch - 34f * air,
                    squashX = 1f + 0.09f * crouch - 0.06f * air,
                    squashY = 1f - 0.09f * crouch + 0.07f * air
                )
            }
            "leg_jump_down" -> {
                val air = if (p < 0.45f) (0.5f + 0.5f * cos((p / 0.45f) * PI)).toFloat() else 0f
                val land = if (p >= 0.40f) smoothPulse((p - 0.40f) / 0.60f) else 0f
                ModularActionSample(
                    track = ModularActionTrack.LEGS,
                    leftLegDelta = -18f * air - 44f * land,
                    leftKneeDelta = 38f * air + 86f * land,
                    rightLegDelta = -16f * air - 44f * land,
                    rightKneeDelta = 36f * air + 86f * land,
                    torsoTiltDelta = 14f * land,
                    bodyBobDelta = -22f * air + 16f * land,
                    squashX = 1f + 0.10f * land - 0.05f * air,
                    squashY = 1f - 0.10f * land + 0.05f * air
                )
            }
            "leg_kick", "act_leg_kick" -> {
                val snap = pulse
                ModularActionSample(
                    track = ModularActionTrack.LEGS,
                    leftLegDelta = 14f * snap,
                    leftKneeDelta = 12f * snap,
                    rightLegDelta = -92f * snap,
                    rightKneeDelta = 26f * (1f - snap) * snap * 2f,
                    torsoTiltDelta = -14f * snap
                )
            }
            "leg_kicking_ball" -> {
                val swing = if (p < 0.36f) smoothPulse(p / 0.36f) else 0f
                val strike = if (p >= 0.34f) smoothPulse((p - 0.34f) / 0.66f) else 0f
                ModularActionSample(
                    track = ModularActionTrack.LEGS,
                    leftLegDelta = 16f * strike,
                    leftKneeDelta = 14f,
                    rightLegDelta = 40f * swing - 76f * strike,
                    rightKneeDelta = 52f * swing + 12f * strike,
                    torsoTiltDelta = 6f * swing - 10f * strike
                )
            }
            "leg_leaning_on_wall" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = 6f,
                leftKneeDelta = 6f,
                rightLegDelta = -48f,
                rightKneeDelta = 96f,
                torsoTiltDelta = -10f,
                silhouetteStyle = "figure_four"
            )
            "leg_namaskar_sitting" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -78f,
                leftKneeDelta = 114f,
                rightLegDelta = -76f,
                rightKneeDelta = 112f,
                torsoTiltDelta = 8f * pulse,
                bodyBobDelta = 24f,
                silhouetteStyle = "cross_legged"
            )
            "leg_sitting", "act_leg_sit" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -72f,
                leftKneeDelta = 78f,
                rightLegDelta = -66f,
                rightKneeDelta = 74f,
                torsoTiltDelta = -3f,
                bodyBobDelta = 20f + 1.5f * wave,
                silhouetteStyle = "seated_profile"
            )
            "leg_sitting_floor_idle" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -82f,
                leftKneeDelta = 118f,
                rightLegDelta = -78f,
                rightKneeDelta = 114f,
                torsoTiltDelta = 2.5f * wave,
                bodyBobDelta = 25f,
                silhouetteStyle = "cross_legged"
            )
            "leg_sneak_walk", "act_leg_sneak" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -22f - 22f * wave,
                leftKneeDelta = 48f + 22f * (waveLag.coerceAtLeast(0f)),
                rightLegDelta = -22f + 22f * wave,
                rightKneeDelta = 48f + 22f * ((-waveLag).coerceAtLeast(0f)),
                torsoTiltDelta = 14f,
                bodyBobDelta = 10f - 3f * bounce
            )
            "leg_walk", "act_leg_walk" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -32f * wave,
                leftKneeDelta = 26f * (waveLag.coerceAtLeast(0f)),
                rightLegDelta = 32f * wave,
                rightKneeDelta = 26f * ((-waveLag).coerceAtLeast(0f)),
                torsoTiltDelta = 4f,
                bodyBobDelta = -6f * bounce,
                squashX = 1f + 0.02f * cos2,
                squashY = 1f - 0.02f * cos2
            )
            "leg_walking_slowly" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -19f * wave,
                leftKneeDelta = 16f * (waveLag.coerceAtLeast(0f)),
                rightLegDelta = 19f * wave,
                rightKneeDelta = 16f * ((-waveLag).coerceAtLeast(0f)),
                torsoTiltDelta = 2f,
                bodyBobDelta = -3f * bounce
            )
            "act_leg_sprint" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -58f * wave,
                leftKneeDelta = 34f + 52f * (waveLag.coerceAtLeast(0f)),
                rightLegDelta = 58f * wave,
                rightKneeDelta = 34f + 52f * ((-waveLag).coerceAtLeast(0f)),
                torsoTiltDelta = 15f,
                bodyBobDelta = -14f * bounce,
                squashX = 1f + 0.04f * cos2,
                squashY = 1f - 0.04f * cos2
            )
            "act_leg_squat" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -48f * pulse,
                leftKneeDelta = 86f * pulse,
                rightLegDelta = -48f * pulse,
                rightKneeDelta = 86f * pulse,
                torsoTiltDelta = 12f * pulse,
                bodyBobDelta = 18f * pulse,
                squashX = 1f + 0.07f * pulse,
                squashY = 1f - 0.07f * pulse
            )
            "act_leg_stance" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -24f,
                leftKneeDelta = 14f,
                rightLegDelta = 24f,
                rightKneeDelta = 14f,
                torsoTiltDelta = 4f,
                bodyBobDelta = 3f
            )
            else -> ModularActionSample(track = action.track, propSilhouetteHint = hint)
        }
    }
}
