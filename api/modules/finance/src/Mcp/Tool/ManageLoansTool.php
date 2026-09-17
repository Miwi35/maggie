<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\CreateLoanCommand;
use Maggie\Finance\Message\DeleteLoanCommand;
use Maggie\Finance\Message\UpdateLoanCommand;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\UseCase\GetDebtTimeline;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_loans', description: 'List, create, update or delete loans being repaid, and read the debt timeline. A loan is described by its remaining capital, its monthly payment and its annual rate in BASIS POINTS (350 = 3.50 %); the end date is never given, it is computed from those three. A payment that does not cover the monthly interest is refused, since such a loan would never be repaid. The timeline action amortises every loan month by month over a horizon (60 months by default) and reports when each one frees up, how much monthly payment that releases, and the net saving capacity — reference income minus loan payments minus the lifestyle measured over the last three months. All amounts are integer cents.')]
class ManageLoansTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly LoanRepository $loanRepository,
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $loanId = null,
        ?string $name = null,
        ?int $principalRemainingCents = null,
        ?int $monthlyPaymentCents = null,
        ?int $annualRateBasisPoints = null,
        ?string $lender = null,
        ?int $priority = null,
        ?string $currency = null,
        ?int $horizonMonths = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $principalRemainingCents, $monthlyPaymentCents, $annualRateBasisPoints, $lender, $priority, $currency),
                'update' => $this->update($loanId, $name, $principalRemainingCents, $monthlyPaymentCents, $annualRateBasisPoints, $lender, $priority, $currency),
                'delete' => $this->delete($loanId),
                'timeline' => $this->timeline($horizonMonths),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, delete, or timeline."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode([
            'loans' => array_map(
                fn (Loan $loan) => $this->serialize($loan),
                $this->loanRepository->findByUser($user),
            ),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name, ?int $principalRemainingCents, ?int $monthlyPaymentCents, ?int $annualRateBasisPoints, ?string $lender, ?int $priority, ?string $currency): string
    {
        if ($name === null || $principalRemainingCents === null || $monthlyPaymentCents === null) {
            return json_encode(['error' => 'name, principalRemainingCents and monthlyPaymentCents are required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $stamped = $this->bus->dispatch(new CreateLoanCommand(
            userId: (string) $user->getId(),
            name: $name,
            principalRemainingCents: $principalRemainingCents,
            monthlyPaymentCents: $monthlyPaymentCents,
            annualRateBasisPoints: $annualRateBasisPoints ?? 0,
            lender: $lender,
            priority: $priority ?? 0,
            currency: $currency ?? 'EUR',
        ));

        /** @var Loan $loan */
        $loan = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'loan' => $this->serialize($loan),
        ], JSON_THROW_ON_ERROR);
    }

    private function update(?string $loanId, ?string $name, ?int $principalRemainingCents, ?int $monthlyPaymentCents, ?int $annualRateBasisPoints, ?string $lender, ?int $priority, ?string $currency): string
    {
        if ($loanId === null) {
            return json_encode(['error' => 'loanId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $stamped = $this->bus->dispatch(new UpdateLoanCommand(
            loanId: $loanId,
            name: $name,
            principalRemainingCents: $principalRemainingCents,
            monthlyPaymentCents: $monthlyPaymentCents,
            annualRateBasisPoints: $annualRateBasisPoints,
            lender: $lender,
            priority: $priority,
            currency: $currency,
        ));

        /** @var Loan $loan */
        $loan = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'loan' => $this->serialize($loan),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $loanId): string
    {
        if ($loanId === null) {
            return json_encode(['error' => 'loanId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteLoanCommand(loanId: $loanId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    private function timeline(?int $horizonMonths): string
    {
        $user = $this->userContext->requireUser();

        return json_encode(
            $this->getDebtTimeline->execute($user, $horizonMonths),
            JSON_THROW_ON_ERROR,
        );
    }

    /** @return array<string, mixed> */
    private function serialize(Loan $loan): array
    {
        return [
            'id' => (string) $loan->getId(),
            'name' => $loan->getName(),
            'lender' => $loan->getLender(),
            'principalRemainingCents' => $loan->getPrincipalRemainingCents(),
            'monthlyPaymentCents' => $loan->getMonthlyPaymentCents(),
            'annualRateBasisPoints' => $loan->getAnnualRateBasisPoints(),
            'priority' => $loan->getPriority(),
            'currency' => $loan->getCurrency(),
        ];
    }
}
