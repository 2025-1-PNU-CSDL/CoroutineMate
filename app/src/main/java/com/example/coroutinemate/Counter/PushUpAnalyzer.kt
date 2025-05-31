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
    private val tempRightElbowAngles = mutableListOf<Float>() // 오른쪽 팔꿈치 각도
    private val tempRightHipAngles = mutableListOf<Float>()   // 오른쪽 엉덩이 각도
    private val tempRightKneeAngles = mutableListOf<Float>()  // 오른쪽 무릎 각도

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
    // 왼쪽 관절도 필요한 경우 유사하게 정의 가능

    companion object {
        // 상태 문자열 상수
        private const val STATE_UP = "up"
        private const val STATE_DOWN = "down"

        // 자세 판단을 위한 각도 임계값 (이 값들은 실험을 통해 조정 필요)
        private const val ELBOW_UP_THRESHOLD = 130f     // 팔꿈치가 펴졌다고 판단하는 최소 각도 (UP 자세)
        private const val ELBOW_DOWN_THRESHOLD = 110f   // 팔꿈치가 굽혀졌다고 판단하는 최대 각도 (DOWN 자세)

        // 몸통 일직선 유지를 위한 엉덩이 및 무릎 각도 범위
        private const val HIP_ANGLE_MIN = 140f
        private const val HIP_ANGLE_MAX = 220f          // Dart 코드 값(220), 너무 크면 엉덩이 들림 감지 어려움. 190~200 정도로 조정 고려.
        private const val KNEE_ANGLE_MIN = 130f
        private const val KNEE_ANGLE_MAX = 205f         // Dart 코드 값(205), 무릎은 거의 펴져야 하므로 160~180 정도로 조정 고려.

        // 푸시업 준비 자세 판단을 위한 각도 범위
        private const val READY_ELBOW_MIN = 140f
        private const val READY_ELBOW_MAX = 190f
        private const val READY_HIP_MIN = 140f
        private const val READY_HIP_MAX = 190f
        private const val READY_KNEE_MIN = 125f
        private const val READY_KNEE_MAX = 180f

        // 피드백 생성을 위한 각도 임계값
        private const val FEEDBACK_ELBOW_MAX_FOR_NOT_UP_ENOUGH = 160f // 이 각도 미만이면 팔꿈치 덜 폄
        private const val FEEDBACK_ELBOW_MIN_FOR_NOT_DOWN_ENOUGH = 80f  // 이 각도 초과면 팔꿈치 덜 굽힘
        private const val FEEDBACK_HIP_MIN_FOR_TOO_LOW = 160f           // 이 각도 미만이면 엉덩이 처짐 (Dart 기준)
        // private const val FEEDBACK_HIP_MAX_FOR_TOO_HIGH = 250f       // 이 각도 초과면 엉덩이 들림 (Dart 기준, 현재 미사용)
        private const val FEEDBACK_KNEE_MIN_FOR_BENT_TOO_MUCH = 130f    // 이 각도 미만이면 무릎 굽힘 (Dart 기준)

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
        tempRightElbowAngles.clear()
        tempRightHipAngles.clear()
        tempRightKneeAngles.clear()
    }

    /**
     * 단일 Pose 객체를 분석하여 푸시업 상태, 횟수, 자세를 판단합니다.
     * 이 함수는 동영상의 각 프레임 또는 실시간 카메라 스트림의 각 프레임에 대해 호출됩니다.
     *
     * @param pose 분석할 ML Kit Pose 객체.
     */
    fun analyzePose(pose: Pose) {
        if (!isProcessing) return // 분석이 활성화된 상태가 아니면 아무것도 하지 않음

        // 오른쪽 관절들의 2D 좌표 추출
        val elbowPoints = getPointsForAngle(pose, rightElbowJoints[0], rightElbowJoints[1], rightElbowJoints[2])
        val hipPoints = getPointsForAngle(pose, rightHipJoints[0], rightHipJoints[1], rightHipJoints[2])
        val kneePoints = getPointsForAngle(pose, rightKneeJoints[0], rightKneeJoints[1], rightKneeJoints[2])

        // 랜드마크가 제대로 감지되었는지 최소한으로 확인 (모든 좌표가 (0,0)이면 유효하지 않다고 간주)
        // 좀 더 엄밀한 유효성 검사가 필요할 수 있음 (예: 특정 랜드마크의 visibility/inFrameLikelihood 확인)
        if (elbowPoints.all { it.x == 0f && it.y == 0f } ||
            hipPoints.all { it.x == 0f && it.y == 0f } ||
            kneePoints.all { it.x == 0f && it.y == 0f }) {
            Log.w("PushUpAnalyzer", "Essential landmarks not detected reliably in the current frame.")
            return // 필수 랜드마크 없이는 분석 불가
        }

        // 각 관절의 각도 계산
        val rightElbowAngle = calculateAngle2D(elbowPoints[0], elbowPoints[1], elbowPoints[2])
        val rightHipAngle = calculateAngle2D(hipPoints[0], hipPoints[1], hipPoints[2])
        val rightKneeAngle = calculateAngle2D(kneePoints[0], kneePoints[1], kneePoints[2])

        // 디버깅을 위한 각도 로그 (필요시 활성화)
        Log.d("PushUpAnalyzer", "Angles - Elbow: ${rightElbowAngle.toInt()}, Hip: ${rightHipAngle.toInt()}, Knee: ${rightKneeAngle.toInt()}, State: $currentState, Ready: $isUserReady")

        // 1. 사용자 준비 자세 확인 (아직 준비되지 않았다면)
        if (!isUserReady) {
            // 팔꿈치, 엉덩이, 무릎이 적절히 펴진 'UP' 자세인지 확인
            val isReadyPose = rightElbowAngle > READY_ELBOW_MIN && rightElbowAngle < READY_ELBOW_MAX &&
                    rightHipAngle > READY_HIP_MIN && rightHipAngle < READY_HIP_MAX &&
                    rightKneeAngle > READY_KNEE_MIN && rightKneeAngle < READY_KNEE_MAX
            if (isReadyPose) {
                isUserReady = true // 준비 상태로 변경
                listener.onPushUpReady() // 리스너에 준비 완료 알림
                Log.i("PushUpAnalyzer", "User is ready for push-ups. Initial pose detected.")
                // 준비 자세에서 얻은 각도는 실제 카운팅에 사용하지 않도록, 이 프레임은 여기서 분석 종료
                return
            } else {
                // 아직 준비 자세가 아니면 다음 프레임에서 계속 확인
                return
            }
        }

        // 2. 준비 자세가 확인된 후, 현재 프레임의 각도를 임시 리스트에 추가
        // 이 각도들은 한 푸시업 사이클의 최소/최대 각도를 판단하여 피드백 생성에 사용됨
        tempRightElbowAngles.add(rightElbowAngle)
        tempRightHipAngles.add(rightHipAngle)
        tempRightKneeAngles.add(rightKneeAngle)

        // 3. 상태 전이 및 카운트 로직
        // 현재 자세 판단을 위한 조건들
        val isElbowUp = rightElbowAngle > ELBOW_UP_THRESHOLD      // 팔꿈치가 충분히 펴졌는가?
        val isElbowDown = rightElbowAngle < ELBOW_DOWN_THRESHOLD  // 팔꿈치가 충분히 굽혀졌는가?

        // 몸통이 비교적 일직선을 유지하는지 (엉덩이와 무릎 각도 확인)
        val isHipAligned = rightHipAngle > HIP_ANGLE_MIN && rightHipAngle < HIP_ANGLE_MAX
        val isKneeAligned = rightKneeAngle > KNEE_ANGLE_MIN && rightKneeAngle < KNEE_ANGLE_MAX
        val isLowerBodyStable = isHipAligned && isKneeAligned // 엉덩이와 무릎 모두 안정적인가?

        // 상태 전이: DOWN -> UP (푸시업 1회 완료)
        if (currentState == STATE_DOWN && isElbowUp && isLowerBodyStable) {
            currentCount++ // 카운트 증가
            currentState = STATE_UP // 상태를 'UP'으로 변경
            Log.i("PushUpAnalyzer", "Push-up COUNT: $currentCount (Transitioned to UP)")

            listener.onCountChanged(currentCount) // 리스너에 카운트 변경 알림
            listener.onStateChanged(currentState) // 리스너에 상태 변경 알림

            generateFeedback() // 방금 완료한 푸시업에 대한 피드백 생성
            resetTempAngles()  // 다음 사이클을 위해 임시 각도 리스트 초기화

            // 목표 횟수 달성 여부 확인 (targetCount가 0보다 클 경우)
            if (targetCount > 0 && currentCount >= targetCount) {
                listener.onTargetReached()
                Log.i("PushUpAnalyzer", "Target count of $targetCount reached.")
                // 동영상 분석에서는 목표 도달 후에도 계속 분석할 수 있으므로, 여기서 stopProcessing()을 호출하지 않음.
                // 실시간 분석이라면 여기서 stopProcessing()을 호출하여 자동 중지시킬 수 있음.
            }
        }
        // 상태 전이: UP -> DOWN (푸시업 내려가는 동작 시작)
        else if (currentState == STATE_UP && isElbowDown && isLowerBodyStable) {
            currentState = STATE_DOWN // 상태를 'DOWN'으로 변경
            // 'DOWN' 상태 진입 시간을 기록 (푸시업 속도 계산용).
            // 동영상 분석의 경우, 프레임의 타임스탬프를 사용하는 것이 더 정확할 수 있음.
            lastPushUpStartTimeMs = System.currentTimeMillis()
            Log.d("PushUpAnalyzer", "Push-up state: Transitioned to DOWN")
            listener.onStateChanged(currentState) // 리스너에 상태 변경 알림
            // 'DOWN' 상태에서는 임시 각도 리스트를 초기화하지 않고 계속 누적 (내려가는 동안의 모든 각도 기록)
        }
    }

    /**
     * 한 푸시업 사이클이 완료된 후, 해당 사이클 동안의 각도들을 바탕으로 자세 피드백을 생성합니다.
     * 생성된 피드백은 feedbackLog에 기록되고 리스너를 통해 전달됩니다.
     */
    private fun generateFeedback() {
        // 임시 각도 데이터가 없으면 피드백 생성 불가
        if (tempRightElbowAngles.isEmpty()) {
            Log.w("PushUpAnalyzer", "Cannot generate feedback: No angle data collected for the cycle.")
            return
        }

        // 이번 사이클 동안의 주요 관절 최소/최대 각도 추출
        val maxElbowAngleInCycle = tempRightElbowAngles.maxOrZero()
        val minElbowAngleInCycle = tempRightElbowAngles.minOrZero()
        val minHipAngleInCycle = tempRightHipAngles.minOrZero()
        // val maxHipAngleInCycle = tempRightHipAngles.maxOrZero() // 엉덩이 너무 높음 피드백에 필요
        val minKneeAngleInCycle = tempRightKneeAngles.minOrZero()

        // 푸시업 한 사이클에 걸린 시간 (DOWN -> UP)
        // lastPushUpStartTimeMs가 0이 아니어야 유효한 시간 계산 가능
        val pushUpDurationMs = if (lastPushUpStartTimeMs > 0) System.currentTimeMillis() - lastPushUpStartTimeMs else 0L

        var identifiedFeedback = PushUpFeedbackType.GOOD_JOB // 기본 피드백은 "좋음"

        // 1. 속도 피드백
        if (pushUpDurationMs > 0 && pushUpDurationMs < PUSHUP_TOO_FAST_DURATION_MS) {
            identifiedFeedback = PushUpFeedbackType.TOO_FAST
        }
        // 2. 팔꿈치 피드백 (속도 문제가 아닐 경우에만 검사, 또는 우선순위 다르게)
        else if (maxElbowAngleInCycle > 0 && maxElbowAngleInCycle < FEEDBACK_ELBOW_MAX_FOR_NOT_UP_ENOUGH) { // 0보다 큰 것은 유효한 값일 때만
            identifiedFeedback = PushUpFeedbackType.NOT_ELBOW_UP_ENOUGH
        } else if (minElbowAngleInCycle > FEEDBACK_ELBOW_MIN_FOR_NOT_DOWN_ENOUGH) {
            identifiedFeedback = PushUpFeedbackType.NOT_ELBOW_DOWN_ENOUGH
        }
        // 3. 엉덩이 피드백 (팔꿈치 문제가 아닐 경우)
        else if (minHipAngleInCycle > 0 && minHipAngleInCycle < FEEDBACK_HIP_MIN_FOR_TOO_LOW) {
            identifiedFeedback = PushUpFeedbackType.HIP_TOO_LOW
        }
        // TODO: HIP_TOO_HIGH 피드백 추가 (maxHipAngleInCycle 사용)
        // else if (maxHipAngleInCycle > FEEDBACK_HIP_MAX_FOR_TOO_HIGH) {
        //    identifiedFeedback = PushUpFeedbackType.HIP_TOO_HIGH
        // }
        // 4. 무릎 피드백 (엉덩이 문제가 아닐 경우)
        else if (minKneeAngleInCycle > 0 && minKneeAngleInCycle < FEEDBACK_KNEE_MIN_FOR_BENT_TOO_MUCH) {
            identifiedFeedback = PushUpFeedbackType.KNEE_BENT_TOO_MUCH
        }

        // 생성된 피드백을 로그에 기록하고 리스너에 전달
        Log.i("PushUpAnalyzer", "Feedback for count $currentCount: $identifiedFeedback. Cycle duration: $pushUpDurationMs ms. Angles (ElbMin/Max,HipMin,KneeMin): ${minElbowAngleInCycle.toInt()}/${maxElbowAngleInCycle.toInt()}, ${minHipAngleInCycle.toInt()}, ${minKneeAngleInCycle.toInt()}")
        feedbackLog.add(Pair(currentCount, identifiedFeedback))
        listener.onFeedback(identifiedFeedback, currentCount)

        // lastPushUpStartTimeMs 초기화 (다음 사이클 준비)
        lastPushUpStartTimeMs = 0L
    }
}