<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Reminder;

use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Maggie\Core\Repository\UserRepository;
use Maggie\MealGrocery\Service\ShortageAlerts;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\AsTaggedItem;

/**
 * The ingredients missing for the meals of today and tomorrow (MAG-570): one
 * interruption per product and meal, settled when covered ({@see ShortageAlerts}).
 */
#[AsTaggedItem(priority: 10)]
final class ShortageReminderSource implements ReminderSourceInterface
{
    public function __construct(
        private readonly ShortageAlerts $shortageAlerts,
        private readonly UserRepository $userRepository,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $created = 0;
        $lines = [];
        $warnings = [];

        foreach ($this->userRepository->findAll() as $user) {
            try {
                $sent = $this->shortageAlerts->run($user);
            } catch (\Throwable $e) {
                $this->logger->error('Shortage alerts failed for {user}: {error}', ['user' => (string) $user->getId(), 'error' => $e->getMessage(), 'exception' => $e]);
                $warnings[] = \sprintf('Shortage alerts failed for %s: %s', $user->getEmail(), $e->getMessage());
                continue;
            }
            if ($sent > 0) {
                $lines[] = \sprintf('  Sent %d shortage alert(s) to %s', $sent, $user->getEmail());
            }
            $created += $sent;
        }

        return new ReminderReport($created, $lines, $warnings);
    }
}
