package com.mundcode.muntam.presentation.screen.exam_record

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mundcode.designsystem.model.SelectableNumber
import com.mundcode.designsystem.model.SelectableTextState
import com.mundcode.domain.model.enums.ExamState
import com.mundcode.domain.model.enums.QuestionState
import com.mundcode.domain.usecase.GetExamByIdFlowUseCase
import com.mundcode.domain.usecase.GetExamByIdUseCase
import com.mundcode.domain.usecase.GetQuestionsByExamIdFlowUseCase
import com.mundcode.domain.usecase.GetQuestionsByExamIdUseCase
import com.mundcode.domain.usecase.GetSubjectByIdFlowUseCase
import com.mundcode.domain.usecase.UpdateExamUseCase
import com.mundcode.domain.usecase.UpdateQuestionUseCase
import com.mundcode.muntam.base.BaseViewModel
import com.mundcode.muntam.navigation.ExamRecord
import com.mundcode.muntam.presentation.model.ExamModel
import com.mundcode.muntam.presentation.model.QuestionModel
import com.mundcode.muntam.presentation.model.asExternalModel
import com.mundcode.muntam.presentation.model.asStateModel
import com.mundcode.muntam.presentation.screen.exam_record.ExamRecordTimer.Companion.DEFAULT_INITIAL_TIME
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExamRecordViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getSubjectByIdFlowUseCase: GetSubjectByIdFlowUseCase,
    private val getExamByIdUseCase: GetExamByIdUseCase,
    private val getExamByIdFlowUseCase: GetExamByIdFlowUseCase,
    private val updateExamUseCase: UpdateExamUseCase,
    private val getQuestionsByExamIdFlowUseCase: GetQuestionsByExamIdFlowUseCase,
    private val getQuestionsByExamIdUseCase: GetQuestionsByExamIdUseCase,
    private val updateQuestionUseCase: UpdateQuestionUseCase
) : BaseViewModel<ExamRecordState>() {
    private val subjectId: Int = checkNotNull(savedStateHandle[ExamRecord.subjectIdArg])
    private val examId: Int = checkNotNull(savedStateHandle[ExamRecord.examIdArg])

    lateinit var timer: ExamRecordTimer

    private val currentState: ExamState get() = state.value.examModel.state
    private val lastQuestionNumber: Int? get() = stateValue.examModel.lastQuestionNumber
    private val currentQuestion: QuestionModel?
        get() {
            val lastQuestionNumber = stateValue.examModel.lastQuestionNumber
            return stateValue.questionModels.find {
                it.questionNumber == lastQuestionNumber
            }
        }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val timeLimit = getSubjectByIdFlowUseCase(subjectId).firstOrNull()?.timeLimit ?: throw Exception()
            val initExam = getExamByIdUseCase(examId).asStateModel()
            val initQuestions = getQuestionsByExamIdUseCase(examId).map { it.asStateModel() }

            updateState {
                stateValue.copy(
                    timeLimit = timeLimit,
                    examModel = initExam,
                    questionModels = initQuestions
                )
            }

            timer = ExamRecordTimer(
                initialTime = initExam.lastAt ?: DEFAULT_INITIAL_TIME,
                timeLimit = initExam.timeLimit,
                initQuestion = initQuestions,
            ) { current, remain, question ->
                updateState {
                    stateValue.copy(
                        currentExamTimeText = current,
                        remainExamTimeText = remain,
                        currentQuestionTimeText = question
                    )
                }
            }

            launch {
                getExamByIdFlowUseCase(examId).collectLatest {
                    Log.e(
                        "SR-N",
                        "exam = state ${it.state} / lastQuestionNumber ${it.lastQuestionNumber}"
                    )

                    updateState {
                        state.value.copy(examModel = it.asStateModel())
                    }

                    val lastQuestionNumber = it.lastQuestionNumber
                    val newQuestion = stateValue.questionModels.find { q ->
                        q.questionNumber == lastQuestionNumber
                    }
                    newQuestion?.let { new ->
                        timer.setCurrentQuestion(new)
                    }
                }
            }


            launch {
                getQuestionsByExamIdFlowUseCase(examId).collectLatest {
                    Log.e(
                        "SR-N",
                        "currentQuestionNumber = ${it.getOrNull((lastQuestionNumber ?: 1) - 1)?.questionNumber}"
                    )

                    updateState {
                        state.value.copy(
                            questionModels = it.map { it.asStateModel() }
                        )
                    }
                }
            }
        }
    }

    fun onClickScreen() = viewModelScope.launch(Dispatchers.IO) {
        Log.d("SR-N", "onClickScreen 시점의 examModel : state ${stateValue.examModel.state} / lastQuestionNumber ${stateValue.examModel.lastQuestionNumber}")
        when (currentState) {
            ExamState.READY -> {
                Log.d("SR-N", "onClickScreen READY")
                updateQuestionState(
                    questionNumber = 1,
                    newQuestionState = QuestionState.RUNNING
                )
                updateExamState(
                    newExamState = ExamState.RUNNING,
                    lastQuestionNumber = 1,
                    lastAt = timer.getCurrentTime()
                )
                timer.start()
            }
            ExamState.PAUSE -> {
                Log.d("SR-N", "onClickScreen PAUSE")
                updateQuestionState(
                    questionNumber = lastQuestionNumber,
                    newQuestionState = QuestionState.RUNNING
                )
                updateExamState(
                    newExamState = ExamState.RUNNING,
                    lastAt = timer.getCurrentTime()
                )
                timer.start()
            }
            ExamState.RUNNING -> {
                Log.d("SR-N", "onClickScreen RUNNING")

                val currentNumber = lastQuestionNumber
                    ?: throw Exception("RUNNING 상태에서 lastQuestionNumber 이 null 일 수 없음.")
                val currentQuestion = currentQuestion
                    ?: throw Exception("RUNNING 상태에서 currentQuestion 이 null 일 수 없음.")
                val currentQuestions = state.value.questionModels

                val nextQuestion: QuestionModel? = currentQuestions.find {
                    // 현재번호보다 큰 번호 먼저
                    it.state == QuestionState.READY && it.questionNumber > currentNumber
                } ?: currentQuestions.find {
                    // 없으면 다른 것중 가장 앞
                    it.state == QuestionState.READY && it.questionNumber != currentNumber
                }


                nextQuestion?.let { next ->// 풀 문제가 있다면
                    lapsAndUpdateQuestion(currentQuestion) // 현재 상태 문제 수정 및 기록

                    updateExamState(
                        newExamState = ExamState.RUNNING,
                        lastQuestionNumber = next.questionNumber,
                        lastAt = timer.getCurrentTime()
                    )

                    updateQuestionUseCase( // 다음 문제 상태 수정
                        next.copy(state = QuestionState.RUNNING).asExternalModel()
                    )
                } ?: run { // 풀 문제가 없다면 종료
                    end()
                }
            }
            ExamState.END -> {
                // todo 동영상광고 보여주고, 문제리스트 화면으로 넘겨주기
            }
        }
    }

    fun onClickBack() = viewModelScope.launch {
        updateState {
            stateValue.copy(
                showBackConfirmDialog = true
            )
        }
        pause()
    }

    fun onClickSetting() {
        // todo Setting 추가
    }

    fun onClickComplete() {
        updateState {
            stateValue.copy(
                showCompleteDialog = true
            )
        }
        pause()
    }

    fun onClickPause() {
        pause()
    }

    fun onClickJump() {
        updateState {
            stateValue.copy(
                showJumpQuestionDialog = true
            )
        }
        pause()
    }

    fun onSelectConfirmBackDialog() {
        onCancelDialog()
        pause()
        updateState {
            stateValue.copy(clickBack = true)
        }
    }

    fun onSelectConfirmCompleteDialog() {
        onCancelDialog()
        end()
    }

    fun onSelectNumberJumpDialog(selectedNumber: Int) = viewModelScope.launch(Dispatchers.IO) {
        onCancelDialog()
        lapsAndUpdateQuestion(currentQuestion)
        updateExamState(ExamState.RUNNING, lastQuestionNumber = selectedNumber)
        updateQuestionState(selectedNumber, newQuestionState = QuestionState.RUNNING)
    }

    private fun pause() = viewModelScope.launch(Dispatchers.IO) {
        timer.pause()
        updateExamState(newExamState = ExamState.PAUSE, lastAt = timer.getCurrentTime())
        lapsAndUpdateQuestion(currentQuestion)
    }

    private fun resume() = viewModelScope.launch(Dispatchers.IO) {
        timer.start()
        updateExamState(newExamState = ExamState.RUNNING)
        updateQuestionState(lastQuestionNumber, QuestionState.RUNNING)
    }

    private fun end() = viewModelScope.launch(Dispatchers.IO) {
        timer.end()
        updateExamState(newExamState = ExamState.END, lastAt = timer.getCurrentTime())
        lapsAndUpdateQuestion(currentQuestion)
        stateValue.questionModels.forEach {
            updateQuestionUseCase(it.copy(state = QuestionState.END).asExternalModel())
        }
        updateState { stateValue.copy(completeAllQuestion = true) }
    }

    fun onCancelDialog() {
        updateState {
            state.value.copy(
                showBackConfirmDialog = false,
                showCompleteDialog = false,
                showJumpQuestionDialog = false
            )
        }
        resume()
    }

    private suspend fun updateExamState(
        newExamState: ExamState,
        lastQuestionNumber: Int? = null,
        lastAt: Long? = null
    ) {
        updateExamUseCase(
            stateValue.examModel.copy(
                state = newExamState,
                lastQuestionNumber = lastQuestionNumber ?: stateValue.examModel.lastQuestionNumber,
                lastAt = lastAt ?: stateValue.examModel.lastAt
            ).asExternalModel()
        )
    }

    private suspend fun updateQuestionState(questionNumber: Int?, newQuestionState: QuestionState) =
        viewModelScope.launch {
            questionNumber?.let { last ->
                stateValue.questionModels.find { it.questionNumber == last }?.let {
                    updateQuestionUseCase(
                        it.copy(state = newQuestionState).asExternalModel()
                    )
                }
            }
        }

    private suspend fun lapsAndUpdateQuestion(current: QuestionModel?) {
        current?.let {
            updateQuestionUseCase(timer.addCompletedQuestion(current).asExternalModel())
        }
    }

    override fun createInitialState(): ExamRecordState {
        return ExamRecordState()
    }

    override fun onCleared() {
        super.onCleared()
        timer.end()
    }
}

data class ExamRecordState(
    val timeLimit: Long = 0,
    val examModel: ExamModel = ExamModel(),
    val currentExamTimeText: String = "00:00:00",
    val remainExamTimeText: String = "00:00:00",
    val currentQuestionTimeText: String = "00:00:00",
    val questionModels: List<QuestionModel> = listOf(),
    val showBackConfirmDialog: Boolean = false,
    val showCompleteDialog: Boolean = false,
    val showJumpQuestionDialog: Boolean = false,
    val completeAllQuestion: Boolean = false,
    val clickBack: Boolean = false
) {
    val selectableNumbers = questionModels.map {
        SelectableNumber(
            number = it.questionNumber,
            state = when(it.state) {
                QuestionState.READY -> SelectableTextState.SELECTABLE
                QuestionState.RUNNING -> SelectableTextState.SELECTED
                else -> SelectableTextState.UNSELECTABLE
            }
        )
    }

    val percent: Float = ((examModel.lastAt?.toFloat() ?: 0f) / timeLimit.toFloat())
}