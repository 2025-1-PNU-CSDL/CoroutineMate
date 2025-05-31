package com.example.coroutinemate.Counter

/**
 * 푸시업 자세 분석 결과로 사용자에게 제공될 수 있는 피드백의 종류를 정의하는 열거형 클래스입니다.
 * 각 피드백 유형은 특정 자세 오류나 긍정적인 평가를 나타냅니다.
 */
enum class PushUpFeedbackType {
    /**
     * 기본 상태 또는 특별한 피드백이 없는 경우 사용될 수 있습니다. (현재 분석기에서는 직접 사용되지 않음)
     */
    NONE,

    /**
     * 팔꿈치를 충분히 펴지 않았을 때 (푸시업 'UP' 자세에서)
     */
    NOT_ELBOW_UP_ENOUGH,

    /**
     * 팔꿈치를 충분히 굽히지 않았을 때 (푸시업 'DOWN' 자세에서)
     */
    NOT_ELBOW_DOWN_ENOUGH,

    /**
     * 엉덩이가 너무 낮게 내려갔을 때 (몸통이 일직선이 아닌 경우)
     */
    HIP_TOO_LOW,

    /**
     * 엉덩이가 너무 높이 올라갔을 때 (몸통이 일직선이 아닌 경우)
     */
    HIP_TOO_HIGH,

    /**
     * 무릎이 과도하게 굽혀졌을 때 (일반적인 푸시업 자세에서 무릎은 펴져 있어야 함)
     */
    KNEE_BENT_TOO_MUCH,

    /**
     * 푸시업 동작 속도가 너무 빠를 때
     */
    TOO_FAST,

    /**
     * 푸시업 동작이 올바르게 수행되었을 때
     */
    GOOD_JOB
}