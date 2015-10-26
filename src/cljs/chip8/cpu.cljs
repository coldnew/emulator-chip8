(ns chip8.cpu
  (:require [chip8.screen :as screen]
            [chip8.keyboard :as keyboard]
            [goog.dom :as dom]
            )
  )

(def fonts
  [0xF0 0x90 0x90 0x90 0xF0 ; 0
   0x20 0x60 0x20 0x20 0x70 ; 1
   0xF0 0x10 0xF0 0x80 0xF0 ; 2
   0xF0 0x10 0xF0 0x10 0xF0 ; 3
   0x90 0x90 0xF0 0x10 0x10 ; 4
   0xF0 0x80 0xF0 0x10 0xF0 ; 5
   0xF0 0x80 0xF0 0x90 0xF0 ; 6
   0xF0 0x10 0x20 0x40 0x40 ; 7
   0xF0 0x90 0xF0 0x90 0xF0 ; 8
   0xF0 0x90 0xF0 0x10 0xF0 ; 9
   0xF0 0x90 0xF0 0x90 0x90 ; A
   0xE0 0x90 0xE0 0x90 0xE0 ; B
   0xF0 0x80 0x80 0x80 0xF0 ; C
   0xE0 0x90 0x90 0x90 0xE0 ; D
   0xF0 0x80 0xF0 0x80 0xF0 ; E
   0xF0 0x80 0xF0 0x80 0x80 ; F
   ])

(defn- make-memory
  "Create CHIP8 memory, which is 4096 bytes (4kb)."
  []
  (let [size 4096]
    (vec
     (take size
           (into fonts
                 (vec (repeat size 0)))))))

(defn- assoc-in-range
  "Update arr with val from start."
  ([arr val] (assoc-in-range arr val 0))
  ([arr val start]
   (let [bound (range (count val))]
     (reduce #(assoc-in %1 [(+ %2 start)] (nth val %2)) arr bound))))

(defn- get-in-range
  "Get arr from start to end."
  ([arr start end] (get-in-range arr (range start end)))
  ([arr rge]
   (reduce #(conj %1 (get-in arr [%2])) [] rge)))

(defn ->bcd
  "Convert val to BCD array.
  ex: (->bcd 123) => [1 2 3]."
  [val]
  [(int (/ val 100))
   (int (/ (mod val 100) 10))
   (int (mod val 10))])

(defn- VxVy [V x y]
  [(nth V x) (nth V y)])

;;;; CPU States

(defn make-cpu []
  {;; CHIP-8 only has 4096 bytes (4KB) memory (0x0 ~ 0xFFF).
   ;;
   ;; The first 512 bytes, from 0x000 to 0x1FF, are where the
   ;; original interpreter was located, and should not used by programs.
   :memory (make-memory)

   ;; The stack is used to remember the current location
   ;; before a jump is performed. The system has 16 levels
   ;; of stack and in order to remember which level of the
   ;; stack is used.
   :stack  (vec (repeat 16 0))

   ;; The stack pointer (SP) can be 8-bit, it is used to point
   ;; to the topmost level of the stack.
   :sp 0

   ;; Most CHIP-8 programs start at locastion 0x200 (512),
   ;; but some begin at 0x600 (1536).
   ;; The program counter (PC) should be 16-bit, and is used
   ;; to store the currently executing address.
   :pc 0x200

   ;; V-registers, CHIP-8 has 15 8-bit general purpose registers
   ;; named V0, V1 ~ VE. The 16th register is used for
   ;; the `VF flag`.
   :v (vec (repeat 16 0))

   ;; I-register is used to store memory address
   :i 0

   ;; The delay timer is active whenever the delay timer
   ;; register (DT) is non-zero.
   ;; This timer does nothing more than subtract 1 from the
   ;; value of DT at a rate of 60Hz. When DT reaches 0, it deactivates.
   :delay-timer 0

   ;; The sound timer is active whenever the sound timer
   ;; register (ST) is non-zero. This timer also decrements
   ;; at a rate of 60Hz, however, as long as ST's value is
   ;; greater than zero, the Chip-8 buzzer will sound. When ST
   ;; reaches zero, the sound timer deactivates.
   :sound-timer 0

   ;; This is special flag for canvas function to know when
   ;; to update the canvas screen. If the flag not zero,
   ;; update the canvas.
   :draw-flag 0
   })

(defn make-vm
  []
  {:cpu (make-cpu)
   :screen (screen/make-screen)
   }
  )

(defn load-rom
  "Load rom to memory. The program will be
  loaded start at 0x200."
  [state rom]
  (.log js/console  "Load ROM --> "  rom)
  (let [mem (:memory (:cpu state))
        c   (count mem)
        p   (count (vec rom))]
    (assoc-in state [:cpu :memory] (assoc-in-range mem (vec rom) 0x200))))

(defn opcode-00E0
  "Clear the screen. This function will also set draw-flag to 1
  to make canvas function refresh."
  [state]
  (-> state
      (assoc :screen (screen/make-screen))
      (assoc-in [:cpu :draw-flag] 1)
      (assoc-in [:cpu :pc] 2)))

(defn opcode-00EE
  "Return from a subroutine."
  [{{:keys [sp stack]} :cpu :as state}]
  (-> state
      (assoc-in [:cpu :sp] (dec sp))
      (assoc-in [:cpu :pc] (+ 2 (nth stack (dec sp))))))

(defn opcode-1NNN
  "Jump to address NNN."
  [state NNN]
  (-> state
      (assoc-in [:cpu :pc] NNN)))

(defn opcode-2NNN
  " Call subroutine at nnn."
  [{{:keys [pc sp stack]} :cpu :as state} NNN]
  (-> state
      (assoc-in [:cpu :stack] (assoc stack sp pc))
      (assoc-in [:cpu :sp] (inc sp))
      (assoc-in [:cpu :pc] NNN)))

(defn opcode-3XNN
  "Skip next instruction if VX = NN."
  [{{:keys [v]} :cpu :as state} X NN]
  (let [Vx (nth v X)
        pc-inc (if (= Vx NN) 4 2)]
    (-> state
        (assoc-in [:cpu :pc] pc-inc))))

(defn opcode-4XNN
  "Skip next instruction if VX != NN."
  [{{:keys [v]} :cpu :as state} X NN]
  (let [Vx (nth v X)
        pc-inc (if-not (= Vx NN) 4 2)]
    (-> state
        (assoc-in [:cpu :pc] pc-inc))))

(defn opcode-5XY0
  "Skip next instruction if Vx = Vy."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)
        pc-inc (if (= Vx Vy) 4 2)]
    (-> state
        (assoc-in [:cpu :pc] pc-inc))))

(defn opcode-6XNN
  "Set Vx to NN."
  [{{:keys [v]} :cpu :as state} X NN]
  (let [v-new (assoc v X NN)]
    (-> state
        (assoc-in [:cpu :v] v-new)
        (assoc-in [:cpu :pc] 2))))

(defn opcode-7XNN
  "Set Vx = Vx + NN."
  [{{:keys [v]} :cpu :as state} X NN]
  (let [Vx (bit-and (+ (nth v X) NN) 0xff)]
    (-> state
        (assoc-in [:cpu :v] (assoc v X Vx))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY0
  "Set Vx = Vy."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [Vy (nth v Y)]
    (-> state
        (assoc-in [:cpu :v] (assoc v X Vy))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY1
  "Set Vx = Vx OR Vy."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)]
    (-> state
        (assoc-in [:cpu :v] (assoc v X (bit-or Vx Vy)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY2
  "Set Vx = Vx AND Vy."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)]
    (-> state
        (assoc-in [:cpu :v] (assoc v X (bit-and Vx Vy)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY3
  "Set Vx = Vx XOR Vy."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)]
    (-> state
        (assoc-in [:cpu :v] (assoc v X (bit-xor Vx Vy)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY4
  "Set Vx = Vx + Vy, set VF = carry."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)
        sum (+ Vx Vy)
        VF (if (> sum 0xFF) 1 0)]
    (-> state
        (assoc-in [:cpu :v] (-> v
                                (assoc X (if (> sum 0xFF) (- sum 256) sum))
                                (assoc 0xF VF)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY5
  "Set Vx = Vx - Vy, set VF = NOT borrow."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)
        sub (- Vx Vy)
        VF (if (> Vx Vy) 0 1)]
    (-> state
        (assoc-in [:cpu :v] (-> v
                                (assoc X (if (< sub 0) (+ sub 256) sub))
                                (assoc 0xF VF)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY6
  "Set Vx = Vx SHR 1."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [Vx (nth v X)
        shr (bit-shift-right Vx 1)
        VF (bit-and Vx 0x1)]
    (-> state
        (assoc-in [:cpu :v] (-> v
                                (assoc X (bit-and VF 0xFF))
                                (assoc v 0xF VF)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XY7
  "Set Vx = Vy - Vx, set VF = NOT borrow."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)
        sub (- Vy Vx)
        VF (if (> Vy Vx) 0 1)]
    (-> state
        (assoc-in [:cpu :v] (-> v
                                (assoc Y sub)
                                (assoc 0xF VF)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-8XYE
  "Set Vx = Vx SHL 1."
  [{{:keys [v]} :cpu :as state} X]
  (let [Vx (nth v X)
        shl (bit-shift-left Vx 1)
        VF (bit-and Vx 0x80)]
    (-> state
        (assoc-in [:cpu :v] (-> v
                                (assoc X shl)
                                (assoc 0xF VF)))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-9XY0
  "Skip next instruction if Vx != Vy."
  [{{:keys [v]} :cpu :as state} X Y]
  (let [[Vx Vy] (VxVy v X Y)
        pc-inc (if-not (= Vx Vy) 4 2)]
    (-> state
        (assoc-in [:cpu :pc] pc-inc))))

(defn opcode-ANNN
  "Set I = NNN."
  [state NNN]
  (-> state
      (assoc-in [:cpu :i] NNN)
      (assoc-in [:cpu :pc] 2)))

(defn opcode-BNNN
  "Jump to location nnn + V0"
  [{{:keys [v]} :cpu :as state} NNN]
  (let [V0 (nth v 0)]
    (-> state
        (assoc-in [:cpu :pc] (+ V0 NNN)))))

(defn opcode-CXNN
  "Set Vx = random byte AND NN"
  [{{:keys [v]} :cpu :as state} X NN]
  (let [Vx (bit-and (rand-int 256) NN)]
    (-> state
        (assoc-in [:cpu :v] (assoc v X Vx))
        (assoc-in [:cpu :pc] 2))))


;; (defn opcode-DXYN
;;   "Display n-byte sprite starting at memory location I at (Vx, Vy),
;;   set VF = collision."
;;   [state X Y N]
;;   (let [V (:v (:cpu state))
;;         Vx (bit-and (rand-int 256) NN)
;;         ]
;;     (-> state
;;         (assoc-in [:cpu :v] (assoc V 0xF 0))
;;         ;;
;;         (assoc-in [:cpu :pc] 2))))


;; TODO: Ex09E

;; TODO: ExA1

(defn opcode-FX07
  "Set Vx = delay timer value."
  [{{:keys [v delay-timer]} :cpu :as state} X]
  (-> state
      (assoc-in [:cpu :v] (assoc v X delay-timer))
      (assoc-in [:cpu :pc] 2)))

;; TODO: Fx0A

(defn opcode-FX15
  "Set delay timer = Vx."
  [{{:keys [v]} :cpu :as state} X]
  (let [Vx (nth v X)]
    (-> state
        (assoc-in [:cpu :delay-timer] Vx)
        (assoc-in [:cpu :pc] 2))))

(defn opcode-FX18
  "Set sound timer = Vx."
  [{{:keys [v]} :cpu :as state} X]
  (let [Vx (nth v X)]
    (-> state
        (assoc-in [:cpu :sound-timer] Vx)
        (assoc-in [:cpu :pc] 2))))

(defn opcode-FX29
  "Set I = location of sprite for digit Vx."
  [{{:keys [v]} :cpu :as state} X]
  (let [Vx (nth v X)]
    (-> state
        (assoc-in [:cpu :i] (* 5 Vx))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-FX33
  "Store BCD representation of Vx in memory locations I, I+1, I+2."
  [{{:keys [i v memory]} :cpu :as state} X]
  (let [Vx (nth v X)
        val (->bcd Vx)]
    (-> state
        (assoc-in [:cpu :memory]
                  (assoc-in-range memory val i))
        (assoc-in [:cpu :pc] 2))))

(defn opcode-FX55
  "Store registers V0 through Vx in memory starting at location I."
  [{{:keys [i v memory]} :cpu :as state} X]
  (-> state
      (assoc-in [:cpu :memory]
                (assoc-in-range memory (get-in-range v 0 (inc X)) i))
      (assoc-in [:cpu :pc] 2)))

(defn opcode-FX65
  "Read registers V0 through Vx from memory starting at location I."
  [{{:keys [i memory]} :cpu :as state} X]
  (-> state
      (assoc-in [:cpu :v]
                (assoc-in-range (:v (:cpu state))
                                (get-in-range memory i (+ i X 1))))
      (assoc-in [:cpu :pc] 2)))

(defn opcode-FX1E
  "Set I = I + Vx."
  [{{:keys [i v]} :cpu :as state} X]
  (let [Vx (nth v X)]
    (-> state
        (assoc-in [:cpu :i] (+ i Vx))
        (assoc-in [:cpu :pc] 2))))


(defn initial-vm [state]

  (-> (make-vm)
      ;; Initial screen canvas
      (screen/initial)

      )
  )

(defn aabb
  [{{:keys [memory pc]} :cpu screen :screen :as state}]
  ;;(let [{pc :pc memory :memory} cpu]
  memory
  ;;  )
  )

(aabb (make-vm))


(comment
  (-> (make-vm)
      (screen/set-pixel 31 31)
      (screen/set-pixel 1 1)
      (screen/set-pixel 0 31)
      (screen/set-pixel 31 0)
      (screen/set-pixel 33 0)
      (screen/set-pixel 63 0)
      (screen/set-pixel 63 31)
      (screen/set-pixel 64 0)
      (screen/render)
      )
  )