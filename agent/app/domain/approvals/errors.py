"""Errors of the approvals domain; the HTTP layer translates them into status codes."""


class ApprovalError(Exception):
    pass


class ApprovalNotFound(ApprovalError):
    def __init__(self, message: str = "Approval not found"):
        super().__init__(message)


class ApprovalNotPending(ApprovalError):
    def __init__(self, message: str = "Approval is not pending"):
        super().__init__(message)


class ApprovalExpired(ApprovalError):
    def __init__(self, message: str = "Approval has expired"):
        super().__init__(message)


class UnknownChannel(ApprovalError):
    def __init__(self, channels: tuple[str, ...] | list[str]):
        super().__init__(f"Unknown channel, one of {', '.join(channels)}")
