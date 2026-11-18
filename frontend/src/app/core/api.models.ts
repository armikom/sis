export type EnrollmentRole = 'OWNER' | 'INSTRUCTOR' | 'ASSISTANT' | 'STUDENT';

/** One course of the signed-in person (GET /api/auth/me). */
export interface MyCourseDto {
  id: number; code: string; name: string; term: string; language: string; role: EnrollmentRole;
  attendanceEnabled: boolean; poolEnabled: boolean; archived: boolean;
}
/** GET /api/auth/me. `staff`: staff of at least one course, or a system admin. */
export interface MeDto {
  id: number; fullName: string; email: string | null; studentNo: string | null; nickname: string;
  username: string | null; systemAdmin: boolean; staff: boolean; courses: MyCourseDto[];
}
/** @deprecated use MeDto */
export type AuthUser = MeDto;
export interface ApiError { code: string; message: string; details?: Record<string, unknown>; }
export interface LinkRequestResult { ok: boolean; onRoster: boolean; }

export interface CourseDto {
  id: number; code: string; name: string; term: string; language: string;
  attendanceEnabled: boolean; poolEnabled: boolean; questionsPerChapter: number; askAiPerDay: number;
  aiContext: string | null; archived: boolean; archivedAt: string | null; createdAt: string;
  rosterCount: number; myRole: EnrollmentRole | null; staff: boolean; owner: boolean;
}
export interface CourseRequest {
  code?: string; name?: string; term?: string; language?: string; attendanceEnabled?: boolean; poolEnabled?: boolean;
  questionsPerChapter?: number; askAiPerDay?: number; aiContext?: string;
}
/** personId / email are set on rows from GET /roster; exam screens also build rows from results. */
export interface RosterEntryDto { personId?: number; studentNo: string; fullName: string; email?: string | null; }
export interface RosterImportResult {
  imported: number; created?: number; updated?: number; reactivated?: number; removed?: number;
  skipped: number; errors: { line: number; message: string }[];
}
export interface StaffMemberDto { personId: number; fullName: string; email: string | null; nickname: string; role: EnrollmentRole; }
export interface PersonDto {
  id: number; fullName: string; email: string | null; studentNo: string | null; username: string | null;
  nickname: string; systemAdmin: boolean; active: boolean; deleted: boolean; hasPassword: boolean;
  createdAt: string; lastLoginAt: string | null;
}
export interface PersonRequest {
  fullName?: string; email?: string; studentNo?: string; username?: string; password?: string;
  active?: boolean; systemAdmin?: boolean;
}
export interface MyAttendanceDto { sessionId: number; title: string; date: string; present: boolean; }
export interface MyResultDto { examId: number; title: string; examDate: string | null; points: number; maxPoints: number; }

export interface ClassroomDto { id: number; name: string; latitude?: number; longitude?: number; radiusM?: number; bleBeaconId?: string; hasBleKey: boolean; }
export interface BleKeyResult { beaconId: string; keyHex: string; }

export interface SessionDto {
  id: number; code: string; courseId: number; courseCode: string; courseName: string;
  classroomId?: number; classroomName?: string; title: string; status: string;
  durationMinutes: number; refreshMs: number; requiredScans: number;
  maxSpanSlots: number; maxTokenAgeMs: number; latencyMarginMs: number;
  defaultLatencyThresholdMs: number; screenAreaRatioThreshold: number;
  geoMode: string; bleMode: string; frameCapture: boolean;
  epochStartMs?: number; openedAt?: string; closesAt?: string; closedAt?: string;
  baselineLatencyMs?: number; baselineAreaRatioMax?: number;
  effectiveLatencyThresholdMs: number; effectiveAreaThreshold: number; thresholdSource: string;
  attendanceCount: number; flaggedPendingCount: number;
  joinUrl?: string; createdAt: string;
}

export interface DisplayConfigDto {
  sessionId: number; code: string; sessionKeyB64: string; epochStartMs: number;
  refreshMs: number; closesAt: string; joinUrl: string; qrBaseUrl: string;
  courseCode: string; title: string;
}

export interface AttendanceDto {
  id: number; studentNo: string; fullName: string; source: string; status: string;
  flags: string[]; teacherDecision: string; present: boolean; pending: boolean;
  minLatencyMs?: number; latencyThresholdMs?: number; thresholdSource?: string;
  areaRatioMax?: number; areaThreshold?: number;
  geoState: string; geoDistanceM?: number; geoAccuracyM?: number;
  clockRttMs?: number; bleVerified: boolean; hasFrame: boolean;
  userAgent?: string; cameraLabel?: string; deviceHint?: string; teacherNote?: string;
  createdAt: string; decidedAt?: string;
}

export interface AttemptDto {
  studentNo: string; resultCode: string; deviceHint?: string;
  conflictStudentNo?: string; createdAt: string;
}
export interface BulkAttendanceResult { added: number; skipped: number; errors: string[]; notInRoster?: string[]; }

export interface CalibrationRunDto { id: number; label: string; minLatencyMs: number; maxAreaRatio: number; validScanCount: number; createdAt: string; }

export interface PublicSessionDto {
  code: string; courseCode: string; courseName: string; title: string; status: string;
  closesAt?: string; refreshMs: number; requiredScans: number;
  geoMode: string; bleMode: string; frameCapture: boolean;
  privacyNotice?: string; studentNoPattern: string;
}

export interface CheckinStartResponse { attemptId: string; expiresAt: string; studentName?: string; }
export interface ScanResponse { valid: boolean; reason?: string; distinctValid: number; required: number; complete: boolean; }
export interface BleNonceResponse { nonce: string; expiresInMs: number; serviceUuid: string; nonceCharUuid: string; responseCharUuid: string; beaconName?: string; }
export interface BleVerifyResponse { verified: boolean; }
export interface CompleteResponse { result: string; studentNo: string; studentName?: string; recordedAt: string; }

export interface AttemptRejectedEvent {
  studentNo: string; code: string; deviceHint?: string; conflictStudentNo?: string; at: string;
}
export interface SessionSseEvent { status: string; closesAt?: string; attendanceCount: number; flaggedPendingCount: number; }

/* Exams (docs/11-EXAMS.md) */
export type QuestionType = 'CHOICE' | 'OPEN' | 'FREE';
export interface QuestionDto {
  id: number; position: number; type: QuestionType; text: string | null; topic: string | null;
  maxPoints: number; choiceCount: number | null; correctChoice: string | null; poolQuestionId?: number | null;
}
export interface QuestionRequest {
  id?: number | null; type: QuestionType; text?: string | null; topic?: string | null;
  maxPoints: number; choiceCount?: number | null; correctChoice?: string | null;
}
export interface ExamDto {
  id: number; courseId: number; courseCode: string; courseName: string; title: string;
  examDate: string | null; forms?: number; seed?: number | null;
  totalPoints: number; resultCount: number; createdAt: string; questions: QuestionDto[];
}
export interface ExamSummaryDto {
  id: number; courseId: number; title: string; examDate: string | null; questionCount: number;
  totalPoints: number; resultCount: number; averagePoints: number | null; createdAt: string;
}
export interface ExamRequest { title: string; examDate?: string | null; questions: QuestionRequest[]; }
export interface ExamAnswerDto { questionId: number; answer: string | null; points: number | null; }
export interface ExamResultDto {
  id: number; personId?: number; studentNo: string; fullName: string; form?: string | null; note: string | null; totalPoints: number;
  gradedCount: number; complete: boolean; updatedAt: string; answers: ExamAnswerDto[];
}
export interface ExamResultRequest { fullName?: string | null; note?: string | null; answers: ExamAnswerDto[]; }

export interface QuestionAnalysisDto {
  questionId: number; position: number; type: QuestionType; topic: string | null; maxPoints: number;
  gradedCount: number; averagePoints: number | null; successRate: number | null;
  /** CHOICE only; key "" counts blank answers. */
  choiceCounts: Record<string, number> | null;
}
export interface ExamTopicAnalysisDto { topic: string; questionCount: number; maxPoints: number; successRate: number | null; weakStudentCount: number; }
export interface ExamStudentAnalysisDto { studentNo: string; fullName: string; totalPoints: number; percent: number | null; complete: boolean; weakTopics: string[]; }
export interface ExamAnalysisDto {
  examId: number; totalPoints: number; resultCount: number;
  average: number | null; median: number | null; min: number | null; max: number | null;
  questions: QuestionAnalysisDto[]; topics: ExamTopicAnalysisDto[]; students: ExamStudentAnalysisDto[];
}

export interface WeakStudentDto { studentNo: string; fullName: string; successRate: number; }
export interface CourseTopicDto { topic: string; questionCount: number; successRate: number | null; weakStudents: WeakStudentDto[]; }
export interface CourseStudentDto { studentNo: string; fullName: string; examCount: number; percent: number | null; weakTopics: string[]; }
export interface CourseLearningDto { courseId: number; exams: ExamSummaryDto[]; topics: CourseTopicDto[]; students: CourseStudentDto[]; }
export interface StudentExamDto { examId: number; title: string; examDate: string | null; totalPoints: number; maxPoints: number; percent: number | null; complete: boolean; }
export interface StudentTopicDto { topic: string; questionCount: number; earnedPoints: number; maxPoints: number; successRate: number | null; }
export interface MissedQuestionDto {
  examId: number; examTitle: string; position: number; topic: string | null; text: string | null;
  answer: string | null; correctChoice: string | null; points: number; maxPoints: number;
}
export interface StudentLearningDto {
  courseId: number; studentNo: string; fullName: string;
  exams: StudentExamDto[]; topics: StudentTopicDto[]; missedQuestions: MissedQuestionDto[];
}
