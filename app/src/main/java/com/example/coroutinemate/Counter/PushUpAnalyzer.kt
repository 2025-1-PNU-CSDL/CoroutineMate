package com.example.coroutinemate.Counter

import androidx.compose.runtime.snapshots.toInt
import androidx.compose.ui.geometry.isEmpty

import android.graphics.PointF // PoseUtils에서 사용
import com.google.mlkit.vision.pose.Pose // ML Kit Pose 객체
import com.google.mlkit.vision.pose.PoseLandmark // 랜드마크 타입 정의
import com.example.coroutinemate.Counter.PoseUtils.calculateAngle2D // PoseUtils의 각도 계산 함수
import com.example.coroutinemate.Counter.PoseUtils.getPointsForAngle // PoseUtils의 랜드마크 추출 함수
import com.example.coroutinemate.Counter.PoseUtils.minOrZero // PoseUtils의 확장 함수
import com.example.coroutinemate.Counter.PoseUtils.maxOrZero // PoseUtils의 확장 함수
import android.util.Log // 안드로이드 로깅 유틸리티

/**
 * ML Kit Pose 객체를 분석하여 푸시업 횟수를 계산하고 자세에 대한 피드백을 제공하는 클래스.
 * 동영상 프레임별로 호출되어 자세를 분석합니다.
 *
 * @param targetCount 목표 푸시업 횟수. 0 또는 음수이면 무제한으로 간주합니다.
 * @param listener 분석 결과를 전달받을 리스너 인터페이스.
 */
class PushUpAnalyzer(
    private val targetCount: Int,
    private val listener: PushUpListener
) {

    /**
     * PushUpAnalyzer의 분석 결과를 외부로 전달하기 위한 리스너 인터페이스입니다.
     */
    interface PushUpListener {
        /**
         * 푸시업 횟수가 변경될 때 호출됩니다.
         * @param newCount 새로 변경된 푸시업 횟수.
         */
        fun onCountChanged(newCount: Int)

        /**
         * 푸시업 상태('up' 또는 'down')가 변경될 때 호출됩니다.
         * @param newState 새로 변경된 상태 문자열 ("up" 또는 "down").
         */
        fun onStateChanged(newState: String)

        /**
         * 자세에 대한 피드백이 생성될 때 호출됩니다.
         * @param feedback 생성된 피드백의 유형 (PushUpFeedbackType).
         * @param countAtFeedback 피드백이 생성된 시점의 푸시업 횟수.
         */
        fun onFeedback(feedback: PushUpFeedbackType, countAtFeedback: Int)

        /**
         * 목표 푸시업 횟수에 도달했을 때 호출됩니다. (targetCount가 0보다 클 경우)
         */
        fun onTargetReached()

        /**
         * 사용자가 푸시업 준비 자세(초기 'UP' 자세)를 취한 것으로 감지되었을 때 호출됩니다.
         */
        fun onPushUpReady()

        /**
         * 동영상(또는 입력 스트림)의 모든 프레임 처리가 완료되었을 때 호출됩니다.
         * @param totalCount 최종적으로 카운트된 푸시업 횟수.
         * @param feedbackLog 처리 중 발생한 모든 피드백의 로그 리스트 (횟수, 피드백 유형).
         */
        fun onProcessingComplete(totalCount: Int, feedbackLog: List<Pair<Int, PushUpFeedbackType>>)
    }

    // 현재 푸시업 상태 ("up" 또는 "down")
    private var currentState: String = STATE_UP
    // 현재까지 카운트된 푸시업 횟수
    private var currentCount: Int = 0

    // 한 푸시업 사이클(내려갔다 올라오는 동작) 동안의 각도들을 임시 저장하는 리스트
    private val tempElbowAngles = mutableListOf<Float>() // 오른쪽 팔꿈치 각도
    private val tempHipAngles = mutableListOf<Float>()   // 오른쪽 엉덩이 각도
    private val tempKneeAngles = mutableListOf<Float>()  // 오른쪽 무릎 각도

    // 마지막으로 'down' 상태로 진입한 시간 (푸시업 속도 계산용). 동영상 분석에서는 프레임 시간으로 대체 가능.
    private var lastPushUpStartTimeMs: Long = 0L
    // 현재 분석이 활성화되어 있는지 여부 (동영상 처리 중인지)
    private var isProcessing: Boolean = false
    // 사용자가 푸시업 시작 준비 자세를 취했는지 여부
    private var isUserReady: Boolean = false

    // 분석 중 발생한 모든 피드백을 (횟수, 피드백 유형) 형태로 기록하는 리스트
    private val feedbackLog = mutableListOf<Pair<Int, PushUpFeedbackType>>()

    // 주요 관절의 랜드마크 타입 정의 (오른쪽 기준)
    // 순서: 각도 계산 시 첫 번째 점, 중간 점(꼭짓점), 마지막 점 순서
    private val rightElbowJoints = listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
    private val rightHipJoints = listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
    private val rightKneeJoints = listOf(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
    // 왼쪽 관절도 필요한 경우 유사하게 정의
    private val leftElbowJoints = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
    private val leftHipJoints = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE)
    private val leftKneeJoints = listOf(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)

    companion object {
        // 상태 문자열 상수
        private const val STATE_UP = "up"
        private const val STATE_DOWN = "down"

        // 랜드마크가 유효하다고 판단하는 inFrameLikelihood의 최소값
        private const val LANDMARK_VISIBILITY_THRESHOLD = 0.6f

        // 자세 판단을 위한 각도 임계값

        // UP 자세: 팔꿈치가 충분히 펴졌다고 판단하는 '최소' 각도
        // 예: 팔꿈치 각도가 이 값 '이상'이면 UP 자세로 간주할 수 있음 (다른 조건들과 함께)
        private const val ELBOW_UP_THRESHOLD = 150f

        // DOWN 자세: 팔꿈치가 충분히 굽혀졌다고 판단하는 '최대' 각도
        // 예: 팔꿈치 각도가 이 값 '이하'이면 DOWN 자세로 간주할 수 있음 (다른 조건들과 함께)
        private const val ELBOW_DOWN_THRESHOLD = 100f

        // 몸통 일직선 유지를 위한 엉덩이 및 무릎 각도 범위
        private const val HIP_ANGLE_MIN = 150f
        private const val HIP_ANGLE_MAX = 180f // 최대값

        // 몸통 일직선 유지를 위한 무릎 각도 범위 (거의 펴진 상태)
        private const val KNEE_ANGLE_MIN = 150f
        private const val KNEE_ANGLE_MAX = 180f // 최대값

        // --- 푸시업 준비 자세 판단을 위한 각도 범위 (0~180도 기준) ---
        // (UP 자세와 유사하거나 약간 더 너그러운 범위)
        private const val READY_ELBOW_MIN = 140f       // 팔꿈치가 어느 정도 펴진 상태
        private const val READY_ELBOW_MAX = 180f
        private const val READY_HIP_MIN = 140f         // 엉덩이가 곧게 펴진 상태
        private const val READY_HIP_MAX = 180f
        private const val READY_KNEE_MIN = 140f        // 무릎이 곧게 펴진 상태
        private const val READY_KNEE_MAX = 180f

        // --- 피드백 생성을 위한 각도 임계값 (0~180도 기준) ---

        // UP 자세에서 팔꿈치를 충분히 펴지 않음: 팔꿈치 각도가 이 값 '미만'이면 피드백
        private const val FEEDBACK_ELBOW_MAX_FOR_NOT_UP_ENOUGH = 150f

        // DOWN 자세에서 팔꿈치를 충분히 굽히지 않음: 팔꿈치 각도가 이 값 '초과'면 피드백
        private const val FEEDBACK_ELBOW_MIN_FOR_NOT_DOWN_ENOUGH = 100f

        // 엉덩이가 너무 낮음 (처짐): 엉덩이 각도가 이 값 '미만'이면 피드백
        private const val FEEDBACK_HIP_MIN_FOR_TOO_LOW = 140f

        // 엉덩이가 너무 높음 (들림): 엉덩이 각도가 이 값 '초과'면 피드백 (0-180도 범위에서는 이 피드백이 덜 발생할 수 있음. 엉덩이가 굽혀지는 것은 HIP_TOO_LOW로 감지)
        // 만약 엉덩이가 과도하게 펴져서 아치형이 되는 것을 감지하고 싶다면, HIP_STRAIGHT_MAX (180도)를 약간 넘는 값을 설정할 수 있으나,
        // 일반적인 푸시업에서 엉덩이 들림은 엉덩이 각도가 작아지는 (몸이 V자가 되는) 경우이므로, 이 피드백은 다른 방식으로 고려하거나 제거할 수 있음.
        // 여기서는 엉덩이가 180도를 기준으로 과도하게 펴지는 것을 방지하는 의미로 매우 작은 범위만 허용 (사실상 거의 발생 안 함)
        // 현재 0-180도 체계에서는 HIP_TOO_HIGH는 엉덩이 각도가 매우 클 때(거의 없을 상황)를 의미하게 됨.
        // "엉덩이 들림"은 보통 엉덩이 각도가 180도보다 *작아지면서* 몸이 V자가 되는 것을 의미하는데,
        // 이는 HIP_STRAIGHT_MIN 미만으로 떨어지는 것으로도 볼 수 있음.
        // 일단은 "과도하게 폄(아치형)"으로 해석하여 남겨두나, 사용 빈도가 낮을 수 있음.
        //private const val FEEDBACK_HIP_MAX_FOR_TOO_HIGH = 185f // 또는 이 피드백 조건을 다른 방식으로 생각해야 함 (예: 어깨-엉덩이-발목 각도)

        // 무릎이 너무 굽혀짐: 무릎 각도가 이 값 '미만'이면 피드백
        private const val FEEDBACK_KNEE_MIN_FOR_BENT_TOO_MUCH = 140f

        // 푸시업 속도 판단 임계값 (밀리초)
        private const val PUSHUP_TOO_FAST_DURATION_MS = 1000L // 1초 미만
    }

    /**
     * 푸시업 분석 처리를 시작합니다. 상태를 초기화하고 리스너에 알립니다.
     */
    fun startProcessing() {
        isProcessing = true
        isUserReady = false // 시작 시 준비 상태 초기화
        currentCount = 0
        currentState = STATE_UP
        feedbackLog.clear() // 이전 피드백 로그 초기화
        resetTempAngles()   // 임시 각도 저장 리스트 초기화
        // 리스너를 통해 초기 상태 및 카운트 전달
        listener.onStateChanged(currentState)
        listener.onCountChanged(currentCount)
        Log.i("PushUpAnalyzer", "Processing started. Target: $targetCount")
    }

    /**
     * 푸시업 분석 처리를 중지합니다. 진행 중이었다면 최종 결과를 리스너에 전달합니다.
     */
    fun stopProcessing() {
        if (isProcessing) {
            isProcessing = false
            Log.i("PushUpAnalyzer", "Processing stopped. Final count: $currentCount")
            // 분석 중 발생한 모든 피드백 로그와 함께 최종 카운트 전달
            listener.onProcessingComplete(currentCount, feedbackLog.toList())
        }
    }

    /**
     * 분석기 상태를 초기 상태로 리셋합니다. (카운트, 상태, 피드백 로그 등)
     */
    fun reset() {
        currentCount = 0
        currentState = STATE_UP
        isUserReady = false
        feedbackLog.clear()
        resetTempAngles()
        // 만약 처리 중에 리셋이 호출되면, UI에도 반영하도록 리스너 호출
        if (isProcessing) {
            listener.onStateChanged(currentState)
            listener.onCountChanged(currentCount)
        }
        Log.i("PushUpAnalyzer", "Analyzer state has been reset.")
    }

    /**
     * 한 사이클 동안의 임시 각도 저장 리스트를 비웁니다.
     */
    private fun resetTempAngles() {
        tempElbowAngles.clear()
        tempHipAngles.clear()
        tempKneeAngles.clear()
    }

    /**
     * 단일 Pose 객체를 분석하여 푸시업 상태, 횟수, 자세를 판단합니다.
     * 이 함수는 동영상의 각 프레임 또는 실시간 카메라 스트림의 각 프레임에 대해 호출됩니다.
     *
     * @param pose 분석할 ML Kit Pose 객체.
     */
    fun analyzePose(pose: Pose) {
        if (!isProcessing) return // 분석이 활성화된 상태가 아니면 아무것도 하지 않음

        // 오른쪽 관절 각도 및 유효성/신뢰도 가져오기
        val (rightElbowAngle, rightElbowLikelihood) = getAngleIfValid(
            pose,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_WRIST
        )
        val (rightHipAngle, rightHipLikelihood) = getAngleIfValid(
            pose,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.RIGHT_KNEE
        )
        val (rightKneeAngle, rightKneeLikelihood) = getAngleIfValid(
            pose,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_ANKLE
        )
        // 왼쪽 관절 각도 및 유효성/신뢰도 가져오기
        val (leftElbowAngle, leftElbowLikelihood) = getAngleIfValid(
            pose,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_WRIST
        )
        val (leftHipAngle, leftHipLikelihood) = getAngleIfValid(
            pose,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.LEFT_KNEE
        )
        val (leftKneeAngle, leftKneeLikelihood) = getAngleIfValid(
            pose,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_ANKLE
        )

        // 각 측면의 유효성 판단하기
        // 오른쪽 팔꿈치, 엉덩이, 무릎 각도가 모두 null이 아닌지 확인
        val areRightAnglesValid =
            rightElbowAngle != null && rightHipAngle != null && rightKneeAngle != null
        val areLeftAnglesValid = // 왼쪽도 동일하게 확인
            leftElbowAngle != null && leftHipAngle != null && leftKneeAngle != null

        // 분석 가능한 데이터가 없는 경우 종료
        if (!areRightAnglesValid && !areLeftAnglesValid) {
            Log.w("PushUpAnalyzer", "No valid side detected based on landmark visibility.")
            // 필요시 리스너를 통해 사용자에게 알림
            // listener.onFeedback(PushUpFeedbackType.RETRY_POSE)
            return
        }

        // 사용할 각도 결정하기
        val elbowAngleToUse: Float
        val hipAngleToUse: Float
        val kneeAngleToUse: Float
        var usingSideInfo = "NONE" // 디버깅용

        // 오른쪽이 유효한 경우, 팔꿈치, 엉덩이, 무릎 Likelihood 평균냄
        val overallRightLikelihood =
            if (areRightAnglesValid) (rightElbowLikelihood + rightHipLikelihood + rightKneeLikelihood) / 3f else 0f
        val overallLeftLikelihood = // 왼쪽도 똑같이 평균냄
            if (areLeftAnglesValid) (leftElbowLikelihood + leftHipLikelihood + leftKneeLikelihood) / 3f else 0f

        // 둘 다 유효한 경우, 신뢰도 높은 곳으로 결정
        if (areRightAnglesValid && areLeftAnglesValid) {
            if (overallRightLikelihood >= overallLeftLikelihood) { // 오른쪽 우선 또는 신뢰도 높음
                elbowAngleToUse = rightElbowAngle!! // !! 사용: areRightAnglesValid가 참이므로 null이 아님
                hipAngleToUse = rightHipAngle!!
                kneeAngleToUse = rightKneeAngle!!
                usingSideInfo =
                    "RIGHT (Both valid, R pref/higher L: ${"%.2f".format(overallRightLikelihood)}, L: ${
                        "%.2f".format(overallLeftLikelihood)
                    })"
            } else { // 왼쪽 신뢰도 높음
                elbowAngleToUse = leftElbowAngle!!
                hipAngleToUse = leftHipAngle!!
                kneeAngleToUse = leftKneeAngle!!
                usingSideInfo =
                    "LEFT (Both valid, L higher L: ${"%.2f".format(overallLeftLikelihood)}, R: ${
                        "%.2f".format(overallRightLikelihood)
                    })"
            }
        } else if (areRightAnglesValid) { // 오른쪽만 유효한 경우, 오른쪽 사용
            elbowAngleToUse = rightElbowAngle!!
            hipAngleToUse = rightHipAngle!!
            kneeAngleToUse = rightKneeAngle!!
            usingSideInfo = "RIGHT (Only R valid, L: ${"%.2f".format(overallRightLikelihood)})"
        } else { // 왼쪽만 유효한 경우, 왼쪽 사용
            elbowAngleToUse = leftElbowAngle!!
            hipAngleToUse = leftHipAngle!!
            kneeAngleToUse = leftKneeAngle!!
            usingSideInfo = "LEFT (Only L valid, L: ${"%.2f".format(overallLeftLikelihood)})"
        }

        // 디버깅 로그
        Log.d(
            "PushUpAnalyzer",
            "Angles Used ($usingSideInfo): E:${elbowAngleToUse.toInt()}, H:${hipAngleToUse.toInt()}, K:${kneeAngleToUse.toInt()}"
        )

        // 1. 사용자 준비 자세 확인 (아직 준비되지 않았다면)
        if (!isUserReady) {
            val isReadyPose = // 팔꿈치, 엉덩이, 무릎이 적절히 펴진 'UP' 자세인지 확인
                elbowAngleToUse > READY_ELBOW_MIN && elbowAngleToUse < READY_ELBOW_MAX &&
                        hipAngleToUse > READY_HIP_MIN && hipAngleToUse < READY_HIP_MAX &&
                        kneeAngleToUse > READY_KNEE_MIN && kneeAngleToUse < READY_KNEE_MAX
            if (isReadyPose) {
                isUserReady = true // 준비 상태로 변경
                listener.onPushUpReady() // 리스너에 준비 완료 알림
                Log.i("PushUpAnalyzer", "User is ready. Initial pose detected with $usingSideInfo.")
                return // 준비 자세에서 얻은 각도는 실제 카운팅에 사용하지 않음
            } else {
                return // 아직 준비 자세가 아니면 다음 프레임에서 계속 확인
            }
        }

        // 2. 준비 자세가 확인된 후, 현재 프레임의 각도를 임시 리스트에 추가
        // 이 각도들은 한 푸시업 사이클의 최소/최대 각도를 판단하여 피드백 생성에 사용됨
        tempElbowAngles.add(elbowAngleToUse)
        tempHipAngles.add(hipAngleToUse)
        tempKneeAngles.add(kneeAngleToUse)

        // 3. 상태 전이 및 카운트 로직
        // 현재 자세 판단을 위한 조건들
        val isElbowUp = elbowAngleToUse > ELBOW_UP_THRESHOLD
        val isElbowDown = elbowAngleToUse < ELBOW_DOWN_THRESHOLD
        val isHipAligned = hipAngleToUse > HIP_ANGLE_MIN && hipAngleToUse < HIP_ANGLE_MAX
        val isKneeAligned = kneeAngleToUse > KNEE_ANGLE_MIN && kneeAngleToUse < KNEE_ANGLE_MAX
        val isLowerBodyStable = isHipAligned && isKneeAligned

        if (currentState == STATE_DOWN && isElbowUp && isLowerBodyStable) { // UP 상태로 변경
            currentCount++
            currentState = STATE_UP
            Log.i("PushUpAnalyzer", "Push-up COUNT: $currentCount (Transitioned to UP using $usingSideInfo)")
            listener.onCountChanged(currentCount)
            listener.onStateChanged(currentState)
            generateFeedback()
            resetTempAngles()

            if (targetCount > 0 && currentCount >= targetCount) {
                listener.onTargetReached()
                Log.i("PushUpAnalyzer", "Target count of $targetCount reached.")
            }
        } else if (currentState == STATE_UP && isElbowDown && isLowerBodyStable) { // DOWN 상태로 변경
            currentState = STATE_DOWN
            lastPushUpStartTimeMs = System.currentTimeMillis()
            Log.d("PushUpAnalyzer", "Push-up state: Transitioned to DOWN using $usingSideInfo")
            listener.onStateChanged(currentState)
        }
    }

    /**
     * 특정 세 개의 랜드마크를 받아, 모두 유효한 경우에만 각도를 계산하고, 해당 랜드마크들의 평균 inFrameLikelihood도 반환
     * @param pose 분석할 ML Kit Pose 객체
     * @param lm1Type 첫 번째 랜드마크의 타입 (예: 어깨)
     * @param lm2Type 중심점 랜드마크의 타입 (예: 팔꿈치)
     * @param lm3Type 마지막 랜드마크의 타입 (예: 손목)
     */
    private fun getAngleIfValid(
        pose: Pose,
        lm1Type: Int,
        lm2Type: Int, // 중심점
        lm3Type: Int
    ): Pair<Float?, Float> { // Pair<Angle_Degrees?, AverageLikelihood_of_the_3_landmarks>
        val lm1 = pose.getPoseLandmark(lm1Type) // 각 랜드마크의 객체를 가져옴
        val lm2 = pose.getPoseLandmark(lm2Type)
        val lm3 = pose.getPoseLandmark(lm3Type)

        val landmarks = listOfNotNull(lm1, lm2, lm3)
        var totalLikelihood = 0f
        var validCountForAngle = 0 // 각도 계산을 위해 visibility threshold를 만족하는 랜드마크 수

        landmarks.forEach { lm ->
            totalLikelihood += lm.inFrameLikelihood // null이 아닌 모든 랜드마크의 likelihood 합산
            if (lm.inFrameLikelihood >= LANDMARK_VISIBILITY_THRESHOLD) { // Threshold 이상인 경우만 유효하다고 판단
                validCountForAngle++
            }
        }

        // 세 랜드마크 중 하나라도 없으면 평균 likelihood는 해당 랜드마크들을 기준으로만 계산
        val averageLikelihood = if (landmarks.isNotEmpty()) totalLikelihood / landmarks.size else 0f

        return if (landmarks.size == 3 && validCountForAngle == 3) {
            // 세 랜드마크가 모두 존재하고, 모두 visibility threshold를 만족할 때만 각도 계산
            Pair(
                calculateAngle2D(
                    landmarks[0].position,
                    landmarks[1].position,
                    landmarks[2].position
                ), averageLikelihood
            )
        } else {
            // 그렇지 않으면 각도는 null, 평균 likelihood는 계산된 대로 반환
            Pair(null, averageLikelihood)
        }
    }

    /**
     * 한 푸시업 사이클이 완료된 후, 해당 사이클 동안의 각도들을 바탕으로 자세 피드백을 생성합니다.
     * 생성된 피드백은 feedbackLog에 기록되고 리스너를 통해 전달됩니다.
     */
    private fun generateFeedback() {
        if (tempElbowAngles.isEmpty()) {
            Log.w(
                "PushUpAnalyzer",
                "Cannot generate feedback: No angle data collected for the cycle."
            )
            return
        }

        val maxElbowAngleInCycle = tempElbowAngles.maxOrNull() ?: 0f
        val minElbowAngleInCycle = tempElbowAngles.minOrNull() ?: Float.MAX_VALUE
        val minHipAngleInCycle = tempHipAngles.minOrNull() ?: Float.MAX_VALUE
        val maxHipAngleInCycle = tempHipAngles.maxOrNull() ?: 0f // For HIP_TOO_HIGH
        val minKneeAngleInCycle = tempKneeAngles.minOrNull() ?: Float.MAX_VALUE

        val pushUpDurationMs =
            if (lastPushUpStartTimeMs > 0) System.currentTimeMillis() - lastPushUpStartTimeMs else 0L
        var identifiedFeedback = PushUpFeedbackType.GOOD_JOB

        if (pushUpDurationMs > 0 && pushUpDurationMs < PUSHUP_TOO_FAST_DURATION_MS) {
            identifiedFeedback = PushUpFeedbackType.TOO_FAST
        } else if (maxElbowAngleInCycle > 0 && maxElbowAngleInCycle < FEEDBACK_ELBOW_MAX_FOR_NOT_UP_ENOUGH) { // UP 자세에서 팔꿈치 덜 폄
            identifiedFeedback = PushUpFeedbackType.NOT_ELBOW_UP_ENOUGH
        } else if (minElbowAngleInCycle > 0 && minElbowAngleInCycle > FEEDBACK_ELBOW_MIN_FOR_NOT_DOWN_ENOUGH) { // DOWN 자세에서 팔꿈치 덜 굽힘
            identifiedFeedback = PushUpFeedbackType.NOT_ELBOW_DOWN_ENOUGH
        } else if (minHipAngleInCycle > 0 && minHipAngleInCycle < FEEDBACK_HIP_MIN_FOR_TOO_LOW) {
            identifiedFeedback = PushUpFeedbackType.HIP_TOO_LOW
        }
        //else if (maxHipAngleInCycle > 0 && maxHipAngleInCycle > FEEDBACK_HIP_MAX_FOR_TOO_HIGH) { // 엉덩이 너무 높음
        //    identifiedFeedback = PushUpFeedbackType.HIP_TOO_HIGH
        //}
        else if (minKneeAngleInCycle > 0 && minKneeAngleInCycle < FEEDBACK_KNEE_MIN_FOR_BENT_TOO_MUCH) {
            identifiedFeedback = PushUpFeedbackType.KNEE_BENT_TOO_MUCH
        }

        // 생성된 피드백을 로그에 기록하고 리스너에 전달
        feedbackLog.add(Pair(currentCount, identifiedFeedback))
        listener.onFeedback(identifiedFeedback, currentCount)
        Log.i(
            "PushUpAnalyzer",
            "Feedback for count $currentCount: $identifiedFeedback. Cycle duration: $pushUpDurationMs ms. Angles (E_max:${maxElbowAngleInCycle.toInt()}, E_min:${minElbowAngleInCycle.toInt()}, H_min:${minHipAngleInCycle.toInt()}, H_max:${maxHipAngleInCycle.toInt()}, K_min:${minKneeAngleInCycle.toInt()})"
        )
    }
}