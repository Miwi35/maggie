<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetReferenceSalary;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_reference_salary', description: 'Reads the reference salary: the stable monthly salary the month is judged on, bonuses excluded. No arguments. monthlyCents: positive integer cents, null when unknown. source: declared (net salary declared on the safety cushion, wins), expected (expected amount of recurring incomes in the "Salaire" category), measured (average of their last 12 attached payments), unknown. operations[]: salary recurring operations (id, label, categoryName, period, referenceAmountCents, monthlyCents, source, paymentCount); several employers are added. Changed via manage_safety_cushion or manage_operations.')]
class GetReferenceSalaryTool
{
    public function __construct(
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getReferenceSalary->execute($user), JSON_THROW_ON_ERROR);
    }
}
