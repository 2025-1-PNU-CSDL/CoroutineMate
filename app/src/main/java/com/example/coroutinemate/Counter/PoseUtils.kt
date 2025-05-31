package com.example.coroutinemate.Counter // 본인 프로젝트의 패키지명으로 변경

import android.graphics.PointF // 2D 좌표를 다루기 위한 PointF 클래스
import androidx.compose.ui.tooling.data.position
import com.google.mlkit.vision.pose.Pose // ML Kit의 Pose 객체
import com.google.mlkit.vision.pose.PoseLandmark // ML Kit의 PoseLandmark 객체
import kotlin.math.* // 수학 계산을 위한 함수 (acos, sqrt, toDegrees 등)

/**
 * 자세 분석에 필요한 유틸리티 함수들을 모아놓은 싱글턴 객체입니다.
 */
object PoseUtils {

    /**
     * 특정 랜드마크 타입 세 개에 해당하는 2D 화면 좌표(PointF) 리스트를 반환합니다.
     * 이 함수는 세 점으로 각도를 계산하기 전에 필요한 좌표들을 가져오는 데 사용됩니다.
     *
     * @param pose 감지된 전체 Pose 객체입니다.
     * @param firstLandmarkType 각도의 첫 번째 점에 해당하는 랜드마크 타입 (예: PoseLandmark.LEFT_SHOULDER).
     * @param midLandmarkType 각도의 중간 점(꼭짓점)에 해당하는 랜드마크 타입 (예: PoseLandmark.LEFT_ELBOW).
     * @param lastLandmarkType 각도의 마지막 점에 해당하는 랜드마크 타입 (예: PoseLandmark.LEFT_WRIST).
     * @return 세 랜드마크의 2D 좌표(PointF)를 담은 리스트. 랜드마크를 찾지 못하면 (0f, 0f)를 반환합니다.
     *         (실제 분석에서는 랜드마크가 모두 유효한지 확인하는 로직이 추가적으로 필요할 수 있습니다.)
     */
    fun getPointsForAngle(
        pose: Pose,
        firstLandmarkType: Int,
        midLandmarkType: Int,
        lastLandmarkType: Int
    ): List<PointF> {
        // ML Kit Pose 객체에서 특정 타입의 랜드마크를 가져옵니다.
        val firstLm: PoseLandmark? = pose.getPoseLandmark(firstLandmarkType)
        val midLm: PoseLandmark? = pose.getPoseLandmark(midLandmarkType)
        val lastLm: PoseLandmark? = pose.getPoseLandmark(lastLandmarkType)

        // 랜드마크의 2D 화면 좌표(position)를 사용합니다.
        // 랜드마크가 null일 경우 (감지되지 않았을 경우) 기본값으로 (0f, 0f)를 사용합니다.
        // position3D는 3D 공간 좌표이며, 여기서는 2D 각도 계산을 위해 position (2D)을 사용합니다.
        return listOf(
            PointF(firstLm?.position?.x ?: 0f, firstLm?.position?.y ?: 0f),
            PointF(midLm?.position?.x ?: 0f, midLm?.position?.y ?: 0f),
            PointF(lastLm?.position?.x ?: 0f, lastLm?.position?.y ?: 0f)
        )
    }

    /**
     * 세 점 p1, p2, p3가 주어졌을 때, 점 p2를 꼭짓점으로 하는 각도(벡터 p2p1과 벡터 p2p3 사이)를 계산합니다.
     * 결과 각도는 0도에서 360도 범위로 반환될 수 있습니다. (외적을 사용하여 방향성을 고려)
     * 이 함수는 Dart 코드의 `calculateAngle2D`와 유사한 로직을 따르려고 시도합니다.
     *
     * @param p1 각도의 첫 번째 점 (예: 어깨의 2D 좌표).
     * @param p2 각도의 중간 점(꼭짓점) (예: 팔꿈치의 2D 좌표).
     * @param p3 각도의 마지막 점 (예: 손목의 2D 좌표).
     * @param direction 인체의 촬영 방향을 나타내는 값 (기본값 1).
     *                  Dart 코드에서는 이 값을 사용하여 각도의 360도 표현을 조정했습니다.
     *                  이 값은 외적의 부호와 결합되어 각도가 시계 방향인지 반시계 방향인지를 판단하는 데 사용될 수 있습니다.
     *                  (예: 오른쪽에서 촬영한 영상이면 1, 왼쪽에서 촬영한 영상이면 -1)
     *                  정확한 의미는 Dart 코드의 사용 방식과 일치시켜야 합니다.
     * @return 계산된 각도 (0 ~ 360 범위). 계산이 불가능한 경우 (예: 점이 겹치는 경우) 0f를 반환할 수 있습니다.
     */
    fun calculateAngle2D(p1: PointF, p2: PointF, p3: PointF, direction: Int = 1): Float {
        // 벡터 p2->p1 (점 p2에서 점 p1로 향하는 벡터)
        val p2p1X = p1.x - p2.x
        val p2p1Y = p1.y - p2.y

        // 벡터 p2->p3 (점 p2에서 점 p3로 향하는 벡터)
        val p2p3X = p3.x - p2.x
        val p2p3Y = p3.y - p2.y

        // 두 벡터의 내적(Dot Product) 계산
        // 내적 = |v1| * |v2| * cos(theta)
        val dotProduct = p2p1X * p2p3X + p2p1Y * p2p3Y

        // 각 벡터의 크기(Magnitude) 계산
        val magnitudeP2P1 = sqrt(p2p1X * p2p1X + p2p1Y * p2p1Y)
        val magnitudeP2P3 = sqrt(p2p3X * p2p3X + p2p3Y * p2p3Y)

        var angleRad = 0.0 // 각도를 라디안 단위로 저장할 변수

        // 벡터 크기가 0에 매우 가까운 경우 (점이 거의 겹치는 경우) 계산 오류를 방지
        if (magnitudeP2P1 > 0.0001f && magnitudeP2P3 > 0.0001f) {
            // cos(theta) = 내적 / (|v1| * |v2|)
            val cosTheta = dotProduct / (magnitudeP2P1 * magnitudeP2P3)
            // acos 함수는 [-1, 1] 범위의 값을 받으므로, 부동 소수점 오류로 범위를 벗어나는 것을 방지하기 위해 clamp 처리
            angleRad = acos(max(-1.0f, min(1.0f, cosTheta))).toDouble()
        }

        // 라디안 단위의 각도를 도(Degree) 단위로 변환
        var angleDeg = Math.toDegrees(angleRad).toFloat()

        // 2D 평면에서의 외적(Cross Product)의 Z 성분 계산
        // 벡터 v1=(x1, y1), v2=(x2, y2)일 때, v1 x v2 의 Z 성분은 (x1*y2 - y1*x2)
        // 여기서는 벡터 p2p1과 p2p3를 사용합니다.
        // (p2p1X * p2p3Y - p2p1Y * p2p3X)
        // 이 값의 부호는 두 벡터 사이의 회전 방향(시계/반시계)을 나타냅니다.
        val crossProductZ = p2p1X * p2p3Y - p2p1Y * p2p3X

        // Dart 코드의 로직: if ((externalZ * direction) > 0) { angle = 360 - angle; }
        // Dart 코드의 externalZ가 현재 crossProductZ와 부호가 같거나 반대일 수 있습니다.
        // Dart 코드의 externalZ 계산: (b[0] - a[0]) * (b[1] - c[1]) - (b[1] - a[1]) * (b[0] - c[0])
        // 여기서 a=p1, b=p2, c=p3 라고 가정하면,
        // (p2.x - p1.x) * (p2.y - p3.y) - (p2.y - p1.y) * (p2.x - p3.x)
        // 이는 (-p2p1X) * (-p2p3Y) - (-p2p1Y) * (-p2p3X) 와 유사하며,
        // (p2p1X * p2p3Y) - (p2p1Y * p2p3X) 즉, 현재 crossProductZ와 동일합니다.
        // 따라서 Dart 코드의 조건과 동일하게 적용합니다.
        if ((crossProductZ * direction) > 0) {
            angleDeg = 360f - angleDeg
        }
        // Dart 코드에서는 angle.abs()를 호출하는 부분이 있었지만, acos의 결과는 항상 [0, PI] 이므로
        // angleDeg는 초기에 항상 [0, 180] 범위입니다. 360 - angleDeg 이후에도 양수이므로 abs()는 불필요해 보입니다.

        // NaN (Not a Number) 값이 반환되는 경우를 대비하여 0f로 처리
        return angleDeg.let { if (it.isNaN()) 0f else it }

        /*
         * 대체 각도 계산 방법 (atan2 사용):
         * atan2 함수를 사용하면 0~360도 또는 -180~180도 범위의 각도를 더 직접적으로 얻을 수 있으며,
         * 외적을 사용하는 것보다 직관적일 수 있습니다.
         *
         * val angleRadAtan2P2P1 = atan2(p2p1Y, p2p1X)
         * val angleRadAtan2P2P3 = atan2(p2p3Y, p2p3X)
         * var angleDiffRad = angleRadAtan2P2P3 - angleRadAtan2P2P1
         *
         * // 결과를 0 ~ 2*PI 범위로 정규화
         * if (angleDiffRad < 0) {
         *     angleDiffRad += 2 * PI.toFloat()
         * }
         * var angleDegAtan2 = Math.toDegrees(angleDiffRad.toDouble()).toFloat()
         *
         * // direction 에 따른 조정이 필요하다면 여기에 추가
         * // 예: if (direction == -1 && crossProductZ < 0) angleDegAtan2 = 360f - angleDegAtan2 (조건은 예시)
         *
         * return angleDegAtan2.let { if (it.isNaN()) 0f else it }
         */
    }

    /**
     * Float 리스트에서 최소값을 찾거나, 리스트가 비어있거나 null이면 0f를 반환하는 확장 함수.
     * @receiver 최소값을 찾을 Float 타입의 리스트.
     * @return 리스트의 최소값 또는 0f.
     */
    fun List<Float>.minOrZero(): Float = this.minOrNull() ?: 0f

    /**
     * Float 리스트에서 최대값을 찾거나, 리스트가 비어있거나 null이면 0f를 반환하는 확장 함수.
     * @receiver 최대값을 찾을 Float 타입의 리스트.
     * @return 리스트의 최대값 또는 0f.
     */
    fun List<Float>.maxOrZero(): Float = this.maxOrNull() ?: 0f
}