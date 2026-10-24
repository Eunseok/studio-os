export type Status =
  | "WAITING_APPROVAL"
  | "QUEUED"
  | "ANALYZING"
  | "BLOCKED"
  | "RUNNING"
  | "VERIFYING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";
export type Project = {
  id: string;
  name: string;
  path: string;
  rules: string;
  testCommand: string[];
  createdAt: string;
};
export type Agent = { id: string; name: string; role: string; adapter: string };
export type Requirement = { name: string; available: boolean; detail: string };
export type Task = {
  steeringPending?: boolean; steeringStopped?: boolean; steeringCapability?: string;
  execution?: import("./execution-settings").ExecutionSettings | null;
  sharedDocuments?: {id:string;title:string;revision:number}[];
  appliedCommit?: string;
  autoRouting?: boolean;
  routingPending?: boolean;
  routingError?: string;
  requireReview?: boolean;
  reviewStatus?: string;
  reviewerProvider?: string;
  reviewerModel?: string;
  reviewerReasoning?: string;
  review?: { verdict: string; summary: string; findings: {severity: string; file: string; detail: string}[] };
  failure?: {category: string; adapt: boolean; reason: string};
  attempt?: number;
  previousAttemptId?: string;
  retryTaskId?: string;
  improvementAnalysis?: boolean;
  model?: string;
  reasoning?: string;
  routingReason?: string;
  improvementProposals?: import("./improvements").Proposal[];
  id: string;
  projectId: string;
  agentId: string;
  title: string;
  prompt: string;
  status: Status;
  verification: "NOT_RUN" | "PASSED" | "FAILED";
  adapter: string;
  role: string;
  rules: string;
  testCommand: string[];
  timeoutSeconds: number;
  agentExitCode: number | null;
  testExitCode: number | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  branch: string;
  baseCommit: string;
  worktree: string;
  message: string;
  approvedAt: string | null;
  workflowRunId: string | null;
  workflowNodeId: string | null;
  workflowAttempt: number;
  requireVerification: boolean;
  workflowInputs: { taskId: string; nodeId: string; digest: string }[];
  requiredSkills: string[];
  skills: { id: string; name: string; version: string; digest: string }[];
  requirements: Requirement[];
  cleaned: boolean;
};
export type StudioEvent = {
  id: number;
  type: string;
  taskId: string | null;
  createdAt: string;
  data: Record<string, string>;
};
export type Snapshot = {
  workflows: WorkflowDefinition[];
  workflowRuns: WorkflowRun[];
  skills: import("./academy").Skill[];
  projects: Project[];
  agents: Agent[];
  tasks: Task[];
  eventCursor: number;
  verificationSandbox: boolean;
  adapters: { type: string; requirements: Requirement[] }[];
};

export type WorkflowNode = {
  requireReview?: boolean;
  id: string;
  title: string;
  agentId: string;
  prompt: string;
  dependsOn: string[];
  requiredSkills: string[];
  testCommand: string[] | null;
  requireVerification: boolean;
  timeoutSeconds: number;
  maxAttempts: number;
};
export type WorkflowDefinition = {
  id: string;
  name: string;
  projectId: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
  nodes: WorkflowNode[];
};
export type WorkflowStep = {
  nodeId: string;
  status: Status | "WAITING_DEPENDENCIES";
  message: string;
  attempts: string[];
  outputDigest: string | null;
};
export type WorkflowRun = {
  id: string;
  status: string;
  message: string;
  baseCommit: string;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
  definition: WorkflowDefinition;
  project: Project;
  steps: Record<string, WorkflowStep>;
};
