<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetReferenceSalary;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_reference_salary', description: 'Read the reference salary: the stable monthly salary the user\'s month is judged on, bonuses excluded, rather than what actually came in this month (a salary can arrive late, or two at once). No arguments. monthlyCents is positive integer cents, divide by 100 to speak it ("4 950 € par mois"); it is null when nothing is known, and then never say 0 €: say no salary is set yet, and that declaring it on the safety cushion or creating the recurring income operation "Salaire" fixes that. source says where it comes from, always say it: declared (the net salary the user declared on the safety cushion, it wins), expected (the expected amount of the recurring income operations in the "Salaire" category), measured (the average of their last 12 attached payments, for an operation whose amount is measured), unknown. operations[] lists the salary operations (id, label, categoryName, period, referenceAmountCents, monthlyCents brought to a month, source, paymentCount); two employers are two operations, added. To change the salary use manage_safety_cushion or manage_recurring_operations.')]
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
