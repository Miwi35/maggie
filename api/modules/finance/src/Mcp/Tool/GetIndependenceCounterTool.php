<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_independence_counter', description: 'Read how far the rentes already cover the way the user lives. Both sides are measured over the last three complete months, never declared: passiveIncomeCents is the monthly average of the credits of the categories flagged as rentes (manage_categories, passiveIncome), lifestyleCents is what a month actually costs, loan payments and exceptional movements excluded. coveragePercent is the first over the second and is NOT capped — above 100 the rentes cover more than the month costs. gapCents is what is still missing each month, byCategory breaks the rentes down, and milestones gives 25/50/75/100 % with the monthly rente each one takes. isMeasurable is false when no month has been measured yet, so there is no percentage to give; hasPassiveIncomeCategories is false when the user has not declared any rente category yet — say that, rather than reporting 0 %. coverageWithDebtPercent is the same ratio against the month including loan payments. projectedCoveragePercent and projectedPassiveIncomeCents are a distinct projection, never to be confused with the measured coveragePercent: the same ratio once the rentes the confirmed recurring series still expect (credits of the rente categories, over the current and next two months, no line attached yet) are added to what already landed; give both and say which is which. projection answers « quand serai-je indépendant ? » from the current project planning (get_project_planning): the measured rentes plus the yield, at 4 % a year, of what the planning leaves of the saving capacity each month once the cushion and the projects have taken their share, plus the rent of the investments held (from the month after their purchase until their resale) and, from the month each placement reaches its term, its final capital withdrawn at 4 % a year (a simulation, never a promise). renteSources lists those placements and investments with the monthly rente each counts and what it adds to the coverage in points; they are never part of the measured coveragePercent. nDate is the month (YYYY-MM) the rentes cover the train de vie, null when that is not within 20 years (say so, never give a date then, never blame); curve is the coverage month by month from startMonth to nDate ({month, coverage 0..1}); milestones gives the month of 25/50/75/100 % (startMonth when already behind, null when not reached within 20 years). It is a projection that assumes an intact cushion, stable income and train de vie: give it as such, in facts, never as advice.')]
class GetIndependenceCounterTool
{
    public function __construct(
        private readonly GetIndependenceCounter $getIndependenceCounter,
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

        return json_encode($this->getIndependenceCounter->execute($user), JSON_THROW_ON_ERROR);
    }
}
