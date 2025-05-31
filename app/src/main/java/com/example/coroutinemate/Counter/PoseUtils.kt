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
     * 결과 각도는 0도에서 180도 범위로 반환될 수 있습니다. (외적을 사용하여 방향성을 고려)
     *
     * @param p1 각도의 첫 번째 점 (예: 어깨의 2D 좌표).
     * @param p2 각도의 중간 점(꼭짓점) (예: 팔꿈치의 2D 좌표).
     * @param p3 각도의 마지막 점 (예: 손목의 2D 좌표).
     * @return 계산된 각도 (0 ~ 180 범위). 계산이 불가능한 경우 (예: 점이 겹치는 경우) 0f를 반환할 수 있습니다.
     */
    fun calculateAngle2D(p1: PointF, p2: PointF, p3: PointF): Float {
        val p2p1X = p1.x - p2.x
        val p2p1Y = p1.y - p2.y
        val p2p3X = p3.x - p2.x
        val p2p3Y = p3.y - p2.y

        val dotProduct = p2p1X * p2p3X + p2p1Y * p2p3Y
        val magnitudeP2P1 = sqrt(p2p1X * p2p1X + p2p1Y * p2p1Y)
        val magnitudeP2P3 = sqrt(p2p3X * p2p3X + p2p3Y * p2p3Y)

        var angleRad = 0.0
        if (magnitudeP2P1 > 0.0001f && magnitudeP2P3 > 0.0001f) {
            val cosTheta = dotProduct / (magnitudeP2P1 * magnitudeP2P3)
            angleRad = acos(max(-1.0f, min(1.0f, cosTheta))).toDouble()
        }
        val angleDeg = Math.toDegrees(angleRad).toFloat()
        return angleDeg.let { if (it.isNaN()) 0f else it } // 외적 및 direction 로직 제거
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