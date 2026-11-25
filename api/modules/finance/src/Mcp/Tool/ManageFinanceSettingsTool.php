<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Message\UpdateSafetyCushionCommand;
use Maggie\Finance\Message\UpdateTreasurySettingsCommand;
use Maggie\Finance\UseCase\EnsureSafetyCushion;
use Maggie\Finance\UseCase\GetTreasurySettings;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'manage_finance_settings', description: 'The owner\'s finance parameters, not his figures. Reads or changes the finance settings. Actions: get, update. Treasury: salaryExpectedDay (1-31, last day on shorter months; clearSalaryExpectedDay true forgets it), overdraftLimitCents (0 or more). Safety cushion: targetMonths (1 or more), monthlyNetIncomeCents (0 or more), criticalMonths (0-24, default 2: below it all saving goes to the cushion), cushionSharePercent (0-100, default 60: share of saving going to the cushion above it), rechargeCapCents (0 or more), rechargeTargetMonths (1 or more, indicative). update changes only the given fields, at least one required, all or nothing. Amounts are integer cents. get returns the stored settings only; the cushion state and a late salary are in get_finance_summary.')]
class ManageFinanceSettingsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly GetTreasurySettings $getTreasurySettings,
        private readonly EnsureSafetyCushion $ensureSafetyCushion,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?int $salaryExpectedDay = null,
        ?bool $clearSalaryExpectedDay = null,
        ?int $overdraftLimitCents = null,
        ?int $targetMonths = null,
        ?int $monthlyNetIncomeCents = null,
        ?int $criticalMonths = null,
        ?int $cushionSharePercent = null,
        ?int $rechargeCapCents = null,
        ?int $rechargeTargetMonths = null,
    ): string {
        try {
            return match ($action) {
                'get' => self::json(['settings' => $this->settings($this->userContext->requireUser())]),
                'update' => $this->update($salaryExpectedDay, $clearSalaryExpectedDay ?? false, $overdraftLimitCents, $targetMonths, $monthlyNetIncomeCents, $criticalMonths, $cushionSharePercent, $rechargeCapCents, $rechargeTargetMonths),
                default => self::json(['error' => "Unknown action: {$action}. Use get or update."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage()]);
        }
    }

    /** @return array<string, mixed> */
    private function settings(User $user): array
    {
        $cushion = $this->ensureSafetyCushion->execute($user);

        return GetTreasurySettings::describe($this->getTreasurySettings->forUser($user)) + [
            'targetMonths' => $cushion->getTargetMonths(),
            'monthlyNetIncomeCents' => $cushion->getMonthlyNetIncomeCents(),
            'criticalMonths' => $cushion->getCriticalMonths(),
            'cushionSharePercent' => $cushion->getCushionSharePercent(),
            'rechargeCapCents' => $cushion->getRechargeCapCents(),
            'rechargeTargetMonths' => $cushion->getRechargeTargetMonths(),
        ];
    }

    private function update(?int $salaryExpectedDay, bool $clearDay, ?int $overdraftLimitCents, ?int $targetMonths, ?int $monthlyNetIncomeCents, ?int $criticalMonths, ?int $cushionSharePercent, ?int $rechargeCapCents, ?int $rechargeTargetMonths): string
    {
        $changesDay = null !== $salaryExpectedDay || $clearDay;
        $changesTreasury = $changesDay || null !== $overdraftLimitCents;
        $changesCushion = null !== $targetMonths || null !== $monthlyNetIncomeCents || null !== $criticalMonths
            || null !== $cushionSharePercent || null !== $rechargeCapCents || null !== $rechargeTargetMonths;

        if (!$changesTreasury && !$changesCushion) {
            return self::json(['error' => 'update needs at least one of salaryExpectedDay, clearSalaryExpectedDay, overdraftLimitCents, targetMonths, monthlyNetIncomeCents, criticalMonths, cushionSharePercent, rechargeCapCents or rechargeTargetMonths.']);
        }

        $bounds = [
            'salaryExpectedDay' => [$salaryExpectedDay, 1, 31],
            'overdraftLimitCents' => [$overdraftLimitCents, 0, null],
            'targetMonths' => [$targetMonths, 1, null],
            'monthlyNetIncomeCents' => [$monthlyNetIncomeCents, 0, null],
            'criticalMonths' => [$criticalMonths, 0, 24],
            'cushionSharePercent' => [$cushionSharePercent, 0, 100],
            'rechargeCapCents' => [$rechargeCapCents, 0, null],
            'rechargeTargetMonths' => [$rechargeTargetMonths, 1, null],
        ];
        foreach ($bounds as $field => [$value, $min, $max]) {
            if (null !== $value && ($value < $min || (null !== $max && $value > $max))) {
                return self::json(['error' => null === $max ? "{$field} must be {$min} or more." : "{$field} must be between {$min} and {$max}."]);
            }
        }

        $user = $this->userContext->requireUser();

        if ($changesTreasury) {
            $settings = $this->getTreasurySettings->forUser($user);
            $this->bus->dispatch(new UpdateTreasurySettingsCommand(
                userId: (string) $user->getId(),
                treasurySettingsId: (string) $settings->getId(),
                salaryExpectedDay: $clearDay ? null : $salaryExpectedDay,
                changesSalaryExpectedDay: $changesDay,
                overdraftLimitCents: $overdraftLimitCents,
            ));
        }
        if ($changesCushion) {
            $cushion = $this->ensureSafetyCushion->execute($user);
            $this->bus->dispatch(new UpdateSafetyCushionCommand(
                userId: (string) $user->getId(),
                safetyCushionId: (string) $cushion->getId(),
                targetMonths: $targetMonths,
                monthlyNetIncomeCents: $monthlyNetIncomeCents,
                rechargeCapCents: $rechargeCapCents,
                rechargeTargetMonths: $rechargeTargetMonths,
                criticalMonths: $criticalMonths,
                cushionSharePercent: $cushionSharePercent,
            ));
        }

        return self::json(['success' => true, 'settings' => $this->settings($user)]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
