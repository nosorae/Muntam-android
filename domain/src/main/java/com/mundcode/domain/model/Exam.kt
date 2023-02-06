package com.mundcode.domain.model

import kotlinx.datetime.Instant

data class Exam(
    var id: Int = 0,
    val subjectId: Int,
    val name: String,
    val isFavorite: Boolean,
    val createdAt: Instant,
    val endAt: Instant? = null, // 시험이 끝났을 때 시간
    val lastAt: Instant? = null, // 시험 종료 또는 중간에 나갈 때 마지막으로 기록된 시험진행 시간
    val lastQuestionNumber: Int? = null, // 시험 기록 중 마지막으로 푼 문제
    val deletedAt: Instant? = null, // 소프트 딜리트 용도
    val state: ExamState = ExamState.READY
)
