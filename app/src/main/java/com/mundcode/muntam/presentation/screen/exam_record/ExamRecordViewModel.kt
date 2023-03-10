package com.mundcode.muntam.presentation.screen.exam_record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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
import com.mundcode.muntam.presentation.model.SubjectModel
import com.mundcode.muntam.presentation.model.asExternalModel
import com.mundcode.muntam.presentation.model.asStateModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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
        viewModelScope.launch {
            val initExam = getExamByIdUseCase(examId).asStateModel()
            val initQuestions = getQuestionsByExamIdUseCase(examId).map { it.asStateModel() }

            timer = ExamRecordTimer(
                initialTime = initExam.lastAt ?: 0,
                timeLimit = initExam.timeLimit,
                initQuestion = initQuestions,
                scope = viewModelScope
            ) { sec: Long ->
                updateExamUseCase(initExam.copy(lastAt = sec).asExternalModel())
            }

            getSubjectByIdFlowUseCase(subjectId).collectLatest {
                updateState {
                    state.value.copy(subjectModel = it.asStateModel())
                }
            }

            getExamByIdFlowUseCase(examId).collectLatest {
                updateState {
                    when (it.state) {
                        ExamState.RUNNING -> {
                            timer.start()
                        }
                        else -> {
                            timer.pause()
                        }
                    }
                    state.value.copy(examModel = it.asStateModel())
                }
            }

            getQuestionsByExamIdFlowUseCase(examId).collectLatest {
                updateState {
                    state.value.copy(
                        questionModels = it.map { it.asStateModel() }
                    )
                }
            }
        }
    }

    fun onClickScreen() = viewModelScope.launch(Dispatchers.IO) {
        when (currentState) {
            ExamState.READY -> {
                updateExamState(newExamState = ExamState.RUNNING, lastQuestionNumber = 1)
                updateQuestionState(1, QuestionState.RUNNING)
            }
            ExamState.PAUSE -> {
                updateExamState(newExamState = ExamState.RUNNING)
                updateQuestionState(stateValue.examModel.lastQuestionNumber, QuestionState.RUNNING)
            }
            ExamState.RUNNING -> {
                val currentNumber = lastQuestionNumber ?: 1
                val currentQuestion = currentQuestion
                val currentQuestions = state.value.questionModels

                val nextQuestion: QuestionModel? = currentQuestions.find {
                    it.state != QuestionState.END
                            && it.state != QuestionState.PAUSE
                            && it.questionNumber > currentNumber // 현재번호보다 큰 번호 먼저
                } ?: currentQuestions.find {
                    it.state != QuestionState.END
                            && it.state != QuestionState.PAUSE
                            && it.questionNumber != currentNumber // 없으면 다른 것중 가장 앞
                }


                nextQuestion?.let { next ->// 풀 문제가 있다면
                    updateExamUseCase( // 마지막 문제 번호 수정
                        state.value.examModel.copy(
                            lastQuestionNumber = next.questionNumber
                        ).asExternalModel()
                    )

                    updateQuestionUseCase( // 다음 문제 상태 수정
                        next.copy(state = QuestionState.RUNNING).asExternalModel()
                    )
                    lapsAndUpdateQuestion(currentQuestion) // 현재 상태 문제 수정 및 기록
                } ?: run { // 풀 문제가 없다면 종료
                    updateExamState(ExamState.END)
                    lapsAndUpdateQuestion(currentQuestion)
                    stateValue.questionModels.forEach {
                        updateQuestionUseCase(it.copy(state = QuestionState.END).asExternalModel())
                    }
                    updateState { stateValue.copy(completeAllQuestion = true) }
                }
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

    fun onClickComplete() {
        updateState {
            stateValue.copy(
                showCompleteDialog = true
            )
        }
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

    fun pause() = viewModelScope.launch(Dispatchers.IO) {
        updateExamState(newExamState = ExamState.PAUSE)
        lapsAndUpdateQuestion(currentQuestion)
    }


    fun onSelectConfirmBackDialog() {
        onCancelDialog()
        // todo
    }

    fun onSelectConfirmCompleteDialog() {
        onCancelDialog()
        // todo
    }

    fun onSelectNumberJumpDialog() {
        onCancelDialog()
        // todo
    }

    fun onCancelDialog() {
        updateState {
            state.value.copy(
                showBackConfirmDialog = false,
                showCompleteDialog = false,
                showJumpQuestionDialog = false
            )
        }
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

    private suspend fun lapsAndUpdateQuestion(question: QuestionModel?) {
        question?.let {
            updateQuestionUseCase(timer.addCompletedQuestion(it).asExternalModel())
        }
    }

    override fun createInitialState(): ExamRecordState {
        return ExamRecordState()
    }
}

data class ExamRecordState(
    val subjectModel: SubjectModel = SubjectModel(),
    val examModel: ExamModel = ExamModel(),
    val questionModels: List<QuestionModel> = listOf(),
    val showBackConfirmDialog: Boolean = false,
    val showCompleteDialog: Boolean = false,
    val showJumpQuestionDialog: Boolean = false,
    val completeAllQuestion: Boolean = false
)