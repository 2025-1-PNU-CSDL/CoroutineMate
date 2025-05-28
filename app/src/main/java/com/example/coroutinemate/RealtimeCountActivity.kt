package com.example.coroutinemate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.Executors
import com.example.coroutinemate.Counter.PushUpCounter

class RealtimeCountActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime_count)

        previewView = findViewById(R.id.previewView) // 카메라 화면 표시할 PreviewView 가져오기

        requestCameraPermissionAndStartCamera() // 카메라 Permission 요청하기
    }

    private fun requestCameraPermissionAndStartCamera() {
        // startActivityForResult는 레거시 코드임 -> registerForActivityResult 사용
        // launcher.launch를 통해 ActivityResultContracts.RequestPermission() 권한 요청한 뒤에, 허용/거부 결과를 처리하는 콜백
        val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            }
            else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show() // 카메라 권한 못 가져오면 Toast 메시지 표시
            }
        }
        launcher.launch(Manifest.permission.CAMERA) // 권한 요청 실행
    }

    // Pose Detector 객체 가져오기
    // 높은 정확도를 위해 Accurate Pose Detector 사용
    val poseDetector = PoseDetection.getClient( // ML Kit Pose Detector 클라이언트 생성
        AccuratePoseDetectorOptions.Builder() // Accurate Pose Detector 사용
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE) // STREAM_MODE -> 카메라
            .build() // 객체 생성
    )

    val imageProxyAnalyzer = object : ImageAnalysis.Analyzer { // ImageAnalysis.Analyzer -> 카메라 프레임 분석할 때 호출하는 인터페이스
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) { // analyze -> 새로운 프레임이 들어올 때마다 자동 호출
            val mediaImage = imageProxy.image ?: run { // imageProxy.image -> 실제 프레임 이미지 (Image) 객체
                imageProxy.close() // 이미지가 null이면 리소스 해제 후 리턴
                return
            }
            val rotation = imageProxy.imageInfo.rotationDegrees // ML Kit에 넘길 이미지 회전 각도. 카메라 센서 방향에 따라 회전 정도 저장됨
            val image = InputImage.fromMediaImage(mediaImage, rotation) // ML Kit에서 처리 가능한 InputImage 객체로 변환. 회전 각도 필요

            // 운동 별 카운터
            val countNum = findViewById<TextView>(R.id.countNum)
            val counter = PushUpCounter()

            poseDetector.process(image) // Pose Detector로 포즈 분석 시작. 결과 나오면 콜백(addOn~Listener)으로 전달
                .addOnSuccessListener { pose -> // 포즈 분석 성공
                    counter.process(pose, countNum)
                    Log.d("POSE", "관절 수: ${pose.allPoseLandmarks.size}")
                }
                .addOnFailureListener { e -> // 포즈 분석 실패
                    Log.e("POSE", "오류: $e")
                }
                .addOnCompleteListener { // 마지막에는 항상 imageProxy 닫아줘야 함
                    imageProxy.close()
                }
        }
    }

    // CameraX API 이용해서
    // 1. 프리뷰 화면 띄우고
    // 2. ML Kit 분석기 연결
    private fun startCamera() {
        val cameraProviderFuture =  ProcessCameraProvider.getInstance(this) // CameraX 사용하려면 CameraProvider 객체 필요 -> 비동기적으로 얻으려고 Future 생성

        // Future가 준비되면 실행
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get() // CameraProvider 인스턴스 가져옴 -> 카메라 구성 요소 연결 가능
            val preview = Preview.Builder().build().also { // PreviewView에 카메라 영상 나타나도록 설정
                it.surfaceProvider = previewView.surfaceProvider
            }

            // 카메라 프레임 실시간으로 분석할 이미지 분석기 생성
            // STRATEGY_KEEP_ONLY_LATEST -> 처리 중인 프레임 버리고 가장 최신 프레임만 분석
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, imageProxyAnalyzer) // imageProxyAnalyzer -> 프레임 분석 객체
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA // 전면 카메라 선택 (후면은 DEFAULT_BACK_CAMERA)

            try {
                cameraProvider.unbindAll() // 기존 바인딩된 카메라 리소스 모두 해제
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis // Preview, ImageAnalysis를 액티비티 생명주기와 바인딩 -> 앱이 pause/resume 할 때 자동으로 카메라 리소스 관리
                )
            } catch (e: Exception) {
                Log.e("Camera", "바인딩 실패: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}