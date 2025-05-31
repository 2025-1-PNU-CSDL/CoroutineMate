package com.example.coroutinemate

import android.graphics.Bitmap // 동영상 프레임을 Bitmap으로 다루기 위함
import android.media.MediaMetadataRetriever // 동영상 파일에서 메타데이터 및 프레임을 추출하기 위함
import android.net.Uri // 동영상 파일의 URI를 다루기 위함
import android.os.Bundle // Activity 생명주기 관리를 위함
import android.util.Log // 로깅 유틸리티
import android.view.TextureView
import android.view.View // UI 요소의 가시성 등을 제어하기 위함
import android.widget.Button // UI 버튼
import android.widget.ImageView // 동영상 프레임 미리보기를 위한 UI
import android.widget.ProgressBar // 동영상 처리 진행 상태를 표시하기 위한 UI
import android.widget.TextView // 텍스트 정보를 표시하기 위한 UI
import android.widget.Toast // 간단한 메시지를 사용자에게 보여주기 위함
import androidx.activity.result.contract.ActivityResultContracts // 파일 선택 등 Activity 결과를 받기 위한 최신 방식
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity // 기본 Activity 클래스
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope // Activity의 생명주기와 연동된 코루틴 스코프
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.coroutinemate.Counter.PushUpAnalyzer
import com.example.coroutinemate.Counter.PushUpFeedbackType
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage // ML Kit에 이미지를 입력하기 위한 형식
import com.google.mlkit.vision.pose.Pose // ML Kit Pose 객체
import com.google.mlkit.vision.pose.PoseDetection // PoseDetector 클라이언트를 얻기 위함
import com.google.mlkit.vision.pose.PoseDetector // 자세 감지기 인터페이스
// 'accurate' 모델을 사용하므로, 옵션 설정 시 해당 모델을 명시적으로 사용하거나
// 빌드 시 의존성에 'accurate' 버전이 포함되어 있으면 기본 동작으로 연동될 수 있습니다.
// 여기서는 기본 PoseDetectorOptions를 사용하되, 의존성이 'accurate'로 되어있다고 가정합니다.
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions // 'accurate' 모델 명시적 사용 시
// import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions // 기본 모델 사용 시
import kotlinx.coroutines.* // 코루틴 사용
import kotlinx.coroutines.tasks.await // Google Play Services Task를 코루틴과 함께 사용하기 위함
import java.io.IOException // 파일 입출력 예외 처리를 위함

/**
 * 저장된 동영상을 불러와 푸시업 횟수를 분석하고 결과를 보여주는 Activity 입니다.
 * 메인 스레드에서 ExoPlayer로 UI 상에 영상을 표시하고
 * 백그라운드 스레드에서 ExoPlayer의 프레임을 가져와 ML Kit으로 넘긴 후,
 * ML Kit Pose Detection (Accurate 모델)을 사용하여 각 프레임에서 자세를 감지한 후,
 * PushUpAnalyzer를 통해 푸시업 동작을 분석합니다.
 */
class VideoPushUpAnalyzerActivity : AppCompatActivity(), PushUpAnalyzer.PushUpListener {

    // UI 요소들에 대한 참조 변수
    private lateinit var buttonSelectVideo: Button
    private lateinit var textViewPushUpCount: TextView
    private lateinit var textViewPushUpState: TextView
    private lateinit var textViewPushUpFeedback: TextView
    private lateinit var playerView: PlayerView // 현재 처리 중인 영상을 보여줄 플레이어 뷰
    private lateinit var progressBarVideo: ProgressBar    // 동영상 처리 진행 상태를 나타낼 프로그레스바

    // ML Kit Pose Detector 인스턴스
    private lateinit var poseDetector: PoseDetector
    // 푸시업 분석 로직을 담고 있는 클래스 인스턴스
    private lateinit var pushUpAnalyzer: PushUpAnalyzer

    // processVideo 메서드에서 사용하기 위한 Job
    // videoProcessingJob은 백그라운드 스레드에서 ExoPlayer의 프레임을 가져와 ML Kit으로 넘김
    private var videoProcessingJob: Job? = null

    // ExoPlayer 객체
    private lateinit var exoPlayer: ExoPlayer

    // 동영상 파일을 선택하기 위한 ActivityResultLauncher
    // 사용자가 파일을 선택하면 콜백으로 URI가 전달됩니다.
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedVideoUri ->
            Log.d("VideoPushUpActivity", "Video URI selected: $selectedVideoUri")
            // 새 동영상 선택 시 UI 초기화
            textViewPushUpCount.text = "횟수: 0"
            textViewPushUpState.text = "상태: 대기 중"
            textViewPushUpFeedback.text = "피드백: -"
            progressBarVideo.visibility = View.VISIBLE // 프로그레스바 보이기
            progressBarVideo.progress = 0            // 프로그레스바 진행도 초기화
            pushUpAnalyzer.reset()                   // 분석기 상태 리셋

            // MediaItem 세팅
            val mediaItem = MediaItem.fromUri(selectedVideoUri)
            playerView.player?.setMediaItem(mediaItem)
            playerView.player?.prepare()

            processVideo(selectedVideoUri)           // 선택된 동영상 처리 시작
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity의 레이아웃 설정
        setContentView(R.layout.activity_video_pushup_analyzer) // 레이아웃 파일명과 일치해야 함

        // UI 요소들 초기화
        buttonSelectVideo = findViewById(R.id.buttonSelectVideo)
        textViewPushUpCount = findViewById(R.id.textViewPushUpCount)
        textViewPushUpState = findViewById(R.id.textViewPushUpState)
        textViewPushUpFeedback = findViewById(R.id.textViewPushUpFeedback)

        // PlayerView 초기화 및 ExoPlayer 연결
        playerView = findViewById<PlayerView>(R.id.playerView)
        exoPlayer = ExoPlayer.Builder(this).build() // ExoPlayer 인스턴스 생성
        playerView.player = exoPlayer // 플레이어 뷰에 플레이어 설정

        progressBarVideo = findViewById(R.id.progressBarVideoProcessing)

        // "동영상 선택" 버튼 클릭 리스너 설정
        buttonSelectVideo.setOnClickListener {
            videoProcessingJob?.cancel() // 만약 이전 동영상 처리 작업이 실행 중이었다면 취소
            pickVideoLauncher.launch("video/*") // "video/*" MIME 타입을 가진 파일 선택기 실행
        }

        // ML Kit Accurate Pose Detector 초기화
        // STREAM_MODE는 연속된 이미지(비디오 프레임 등) 처리에 적합합니다.
        // SINGLE_IMAGE_MODE는 단일 정적 이미지 처리에 적합합니다.
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE) // 스트림 모드로 설정
            .build()
        poseDetector = PoseDetection.getClient(options) // 옵션으로 PoseDetector 인스턴스 생성

        // PushUpAnalyzer 초기화, 리스너로 현재 Activity를 전달
        // targetCount를 0으로 설정하여 무제한 푸시업 카운팅 (또는 원하는 목표치 설정 가능)
        pushUpAnalyzer = PushUpAnalyzer(targetCount = 0, listener = this)

        Log.i("VideoPushUpActivity", "Activity created and components initialized.")
    }

    /**
     * 선택된 동영상 URI를 받아 프레임별로 분석을 수행합니다.
     * 코루틴을 사용하여 백그라운드 스레드에서 오래 걸리는 작업을 처리합니다.
     * @param videoUri 분석할 동영상의 URI.
     */
    @OptIn(UnstableApi::class)
    private fun processVideo(videoUri: Uri) {
        // 메인 스레드에서 비디오 재생
        exoPlayer.play()

        // 백그라운드 스레드에서 플레이어의 프레임을 가져와 ML Kit으로 전달
        videoProcessingJob = lifecycleScope.launch(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()
            try {
                // MediaMetadataRetriever에 동영상 데이터 소스 설정
                retriever.setDataSource(applicationContext, videoUri)

                // 동영상의 전체 길이(ms)를 가져옴
                val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val videoDurationMs = durationString?.toLongOrNull() ?: 0L

                // 동영상 길이를 가져오지 못한 경우 오류 처리
                if (videoDurationMs == 0L) {
                    Log.e("VideoPushUpActivity", "Could not get video duration.")
                    withContext(Dispatchers.Main) { // UI 업데이트는 메인 스레드에서
                        Toast.makeText(applicationContext, "동영상 길이 정보를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                        progressBarVideo.visibility = View.GONE
                    }
                    retriever.release() // MediaMetadataRetriever 리소스 해제
                    return@launch // 코루틴 종료
                }

                // PlayerView 내부의 Surface가 TextureView인지 확인하고 가져옴
                val textureView = playerView.videoSurfaceView as? TextureView
                    ?: run {
                        Log.e("Analysis", "PlayerView 내부가 TextureView가 아닙니다! surface_type 확인 필요.")
                        retriever.release()
                        return@launch
                    }

                // PushUpAnalyzer의 처리 시작을 알림
                pushUpAnalyzer.startProcessing()

                // 동영상 프레임 추출 간격 설정 (마이크로초 단위)
                // 예: 100_000L 마이크로초 = 100ms = 0.1초 => 초당 10프레임 분석
                // 이 값은 분석 정확도와 처리 시간 간의 트레이드오프입니다. 줄이면 더 많은 프레임 분석, 늘리면 더 적은 프레임 분석.
                val frameIntervalMs: Long = 50 // 50ms = 약 20 FPS

                Log.i("VideoPushUpActivity", "Starting video processing. Duration: ${videoDurationMs}ms, Frame Interval: ${frameIntervalMs}ms")
                while (isActive) {
                    // exoPlayer 접근하려면 메인 스레드에서 처리해야 함
                    // currentMs: 현재 처리 중인 동영상 시간
                    // isPlaying: 재생 여부
                    val (currentMs, isPlaying) = withContext(Dispatchers.Main) {
                        exoPlayer.currentPosition to exoPlayer.isPlaying
                    }

                    if (currentMs >= videoDurationMs) break // 동영상 끝 도달하면 루프 종료
                    if (!isPlaying) { // 일시 정지한 경우 딜레이만 주고 계속 continue 시켜서 아래 작업 스킵
                        delay(frameIntervalMs)
                        continue
                    }

                    // TextureView에서 현재 프레임 Bitmap 가져오기
                    val bitmap: Bitmap? = textureView.bitmap // 하드웨어 가속으로 버퍼에서 한 번에 가져옴
                    if (bitmap != null) {
                        // ML Kit에 넘기기 위해 InputImage로 변환
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        try {
                            val poseResult: Pose = poseDetector.process(inputImage).await() // ML Kit에 이미지 넘기고 await
                            pushUpAnalyzer.analyzePose(poseResult) // 감지된 Pose 객체를 PushUpAnalyzer로 전달하여 푸시업 분석 수행
                        } catch (e: Exception) {
                            // ML Kit 처리 중 발생할 수 있는 예외 로깅
                            Log.e("VideoPushUpActivity", "Error processing frame with ML Kit at $currentMs ms", e)
                        } finally {
                            bitmap.recycle() // ML Kit 작업 다 끝났으면 비트맵 메모리 해제
                        }

                        // 메인 스레드에서 진행률 계산 및 프로그레스 바 업데이트
                        withContext(Dispatchers.Main) {
                            val progress = (currentMs * 100 / videoDurationMs).toInt()
                            progressBarVideo.progress = progress
                        }
                    }

                    delay(frameIntervalMs) // 다음 분석 시점까지 대기
                }
                Log.i("VideoPushUpActivity", "Video frame processing loop finished.")

            } catch (e: IOException) {
                Log.e("VideoPushUpActivity", "IOException during video processing", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "동영상 처리 중 오류 발생: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                // 코루틴이 취소된 경우 (예: 사용자가 다른 동영상을 선택하거나 Activity가 종료될 때)
                Log.i("VideoPushUpActivity", "Video processing was cancelled.")
                // UI 정리나 특별한 처리가 필요하면 여기에 작성
            } catch (e: Exception) {
                // 기타 예상치 못한 예외 처리
                Log.e("VideoPushUpActivity", "Unexpected error during video processing", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "예상치 못한 오류 발생: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // 모든 처리가 끝나면 (성공, 실패, 취소 모두) MediaMetadataRetriever 리소스 해제
                try {
                    retriever.release()
                } catch (ex: IOException) {
                    Log.e("VideoPushUpActivity", "Error releasing MediaMetadataRetriever", ex)
                }
                // 코루틴이 명시적으로 취소되지 않고 정상적으로 루프를 마쳤다면,
                // PushUpAnalyzer의 처리를 중단시키고 onProcessingComplete 콜백을 유도
                if (isActive) {
                    pushUpAnalyzer.stopProcessing() // 여기서 onProcessingComplete 리스너 메소드 호출
                }
                // 프로그레스바 숨기기 (메인 스레드에서)
                withContext(Dispatchers.Main) {
                    progressBarVideo.visibility = View.GONE
                }
                Log.i("VideoPushUpActivity", "Video processing finished and resources released.")
            }
        }
    }

    // --- PushUpAnalyzer.PushUpListener 인터페이스 구현부 ---

    override fun onCountChanged(newCount: Int) {
        // UI 업데이트는 메인 스레드에서 수행
        runOnUiThread {
            textViewPushUpCount.text = "횟수: $newCount"
        }
    }

    override fun onStateChanged(newState: String) {
        runOnUiThread {
            textViewPushUpState.text = "상태: $newState"
        }
    }

    override fun onFeedback(feedback: PushUpFeedbackType, countAtFeedback: Int) {
        // 동영상 분석에서는 모든 프레임의 피드백을 즉시 UI에 표시하기보다는
        // onProcessingComplete에서 요약된 형태로 보여주는 것이 더 적합할 수 있습니다.
        // 여기서는 로그로만 기록하고, 최종 피드백은 onProcessingComplete에서 처리합니다.
        val feedbackText = when (feedback) {
            PushUpFeedbackType.NOT_ELBOW_UP_ENOUGH -> "팔꿈치 더 펴세요"
            PushUpFeedbackType.NOT_ELBOW_DOWN_ENOUGH -> "팔꿈치 더 굽히세요"
            PushUpFeedbackType.HIP_TOO_LOW -> "엉덩이 낮아요"
            PushUpFeedbackType.HIP_TOO_HIGH -> "엉덩이 높아요"
            PushUpFeedbackType.KNEE_BENT_TOO_MUCH -> "무릎 펴세요"
            PushUpFeedbackType.TOO_FAST -> "너무 빨라요"
            PushUpFeedbackType.GOOD_JOB -> "좋아요!"
            else -> "알 수 없는 피드백"
        }
        Log.i("VideoPushUpListener", "Feedback for count $countAtFeedback: $feedbackText (Raw: $feedback)")
        // 실시간으로 마지막 피드백만 간단히 보여주고 싶다면 아래 주석 해제
        // runOnUiThread {
        //    textViewPushUpFeedback.text = "피드백 ($countAtFeedback): $feedbackText"
        // }
    }

    override fun onTargetReached() {
        Log.i("VideoPushUpListener", "Target push-up count reached.")
        // 동영상 분석에서는 목표 도달 시 특별한 UI 알림을 추가할 수 있습니다.
        // 예: Toast 메시지 표시
        runOnUiThread {
            Toast.makeText(this, "목표 푸시업 ${pushUpAnalyzer}회 달성!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPushUpReady() {
        Log.i("VideoPushUpListener", "User ready state detected in video.")
        runOnUiThread {
            textViewPushUpState.text = "상태: 준비 완료 (분석 진행 중)"
        }
    }

    override fun onProcessingComplete(totalCount: Int, feedbackLog: List<Pair<Int, PushUpFeedbackType>>) {
        // 동영상 전체 분석이 완료된 후 호출됩니다.
        runOnUiThread {
            textViewPushUpCount.text = "최종 횟수: $totalCount"
            textViewPushUpState.text = "상태: 분석 완료"
            progressBarVideo.visibility = View.GONE // 프로그레스바 숨기기

            // 피드백 로그를 문자열로 만들어 TextView에 표시
            val feedbackSummary = feedbackLog.joinToString(separator = "\n") { (count, type) ->
                val typeDescription = when (type) {
                    PushUpFeedbackType.NOT_ELBOW_UP_ENOUGH -> "팔꿈치 덜 폄"
                    PushUpFeedbackType.NOT_ELBOW_DOWN_ENOUGH -> "팔꿈치 덜 굽힘"
                    PushUpFeedbackType.HIP_TOO_LOW -> "엉덩이 낮음"
                    PushUpFeedbackType.HIP_TOO_HIGH -> "엉덩이 높음"
                    PushUpFeedbackType.KNEE_BENT_TOO_MUCH -> "무릎 굽혀짐"
                    PushUpFeedbackType.TOO_FAST -> "속도 빠름"
                    PushUpFeedbackType.GOOD_JOB -> "좋음"
                    else -> type.name // 기본값으로 enum 이름 사용
                }
                "$count 회: $typeDescription"
            }
            textViewPushUpFeedback.text = if (feedbackSummary.isBlank()) "피드백 기록 없음" else "피드백 요약:\n$feedbackSummary"

            Toast.makeText(this, "동영상 분석 완료! 총 $totalCount 회", Toast.LENGTH_LONG).show()
            Log.i("VideoPushUpListener", "Processing complete. Total count: $totalCount. Feedback log size: ${feedbackLog.size}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity가 소멸될 때 진행 중인 코루틴 작업이 있다면 취소합니다.
        videoProcessingJob?.cancel()
        // ML Kit PoseDetector 리소스를 해제합니다. 매우 중요!
        poseDetector.close()
        Log.i("VideoPushUpActivity", "Activity destroyed, resources released (coroutine cancelled, poseDetector closed).")
    }
}