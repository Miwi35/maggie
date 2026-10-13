<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\TreasurySettingsRepository;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Psr\Clock\ClockInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Tells the user, through Maggie, that the salary is late and an overdraft is
 * coming (MAG-281, shape decision 18).
 *
 * The alert is a proaction filed for now in the internal agenda: the agent's
 * executor runs it and the message lands in the conversation. A late salary
 * is an absence — nothing happens that could trigger it — hence a daily run.
 *
 * Maggie only proposes: the prompt forbids her to execute or claim a transfer.
 */
#[AsCommand(
    name: 'app:finance:treasury-alerts',
    description: 'Have Maggie warn the users whose salary is late and who are heading for an overdraft',
)]
class TreasuryAlertsCommand extends Command
{
    /** The overdraft must be forecast within this many days for the alert to be worth it. */
    public const HORIZON_DAYS = 7;

    /** What the agenda reads, and what tells a day's alert from any other proaction. */
    public const SUMMARY = 'Alerte trésorerie';

    private const TIME_ZONE = 'Europe/Paris';

    public function __construct(
        private readonly TreasurySettingsRepository $settingsRepository,
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
        private readonly ProactionRepository $proactionRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly ClockInterface $clock,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $now = $this->clock->now()->setTimezone(new \DateTimeZone(self::TIME_ZONE));
        $today = $now->setTime(0, 0);
        $created = 0;

        foreach ($this->settingsRepository->findWithExpectedDay() as $settings) {
            $user = $settings->getUser();
            $prompt = $this->promptFor($user, $today);
            if (null === $prompt || $this->alreadyAlerted($user, $today)) {
                continue;
            }

            $this->messageBus->dispatch(new ScheduleProactionCommand(
                userId: (string) $user->getId(),
                prompt: $prompt,
                scheduledAt: $now,
                summary: self::SUMMARY,
            ));
            ++$created;
            $io->writeln(sprintf('  Alert filed for %s', $user->getEmail()));
        }

        if (0 === $created) {
            $io->info('No treasury alert to file.');
        } else {
            $io->success(sprintf('Filed %d treasury alert(s).', $created));
        }

        return Command::SUCCESS;
    }

    private function promptFor(User $user, \DateTimeImmutable $today): ?string
    {
        $outlook = $this->getTreasuryOutlook->execute($user, $today);
        $salary = $outlook['salary'];
        $overdraft = $outlook['overdraft'];
        $advice = $outlook['advice'];

        if (!$salary['isConfigured'] || !$salary['isLate'] || !$overdraft['isForecast'] || null === $overdraft['onDate'] || null === $advice) {
            return null;
        }

        if ($overdraft['onDate'] > $today->modify(sprintf('+%d days', self::HORIZON_DAYS))->format('Y-m-d')) {
            return null;
        }

        $lines = [
            "Alerte trésorerie du {$today->format('Y-m-d')}. Le salaire attendu le {$salary['expectedOn']} n'est pas arrivé : "
                .sprintf('%d jour(s) de retard.', $salary['delayDays']),
            sprintf(
                'Le compte courant passerait à découvert le %s (solde le plus bas : %s, limite de découvert autorisée : %s).',
                $overdraft['onDate'],
                self::euros($overdraft['worstBalanceCents']),
                self::euros($overdraft['limitCents']),
            ),
            null !== $advice['sourceAccountName']
                ? sprintf(
                    'Avance conseillée : %s, depuis le compte « %s » (source : get_treasury_outlook, solde du compte %s)%s.',
                    self::euros($advice['amountCents'] - $advice['uncoveredCents']),
                    $advice['sourceAccountName'],
                    self::euros($advice['sourceBalanceCents']),
                    $advice['isCushion'] ? ' — c\'est le matelas de sécurité, à ne toucher qu\'en dernier recours' : '',
                )
                : 'Aucun compte d\'épargne ne peut couvrir une avance (source : get_treasury_outlook).',
        ];
        if ($advice['uncoveredCents'] > 0 && null !== $advice['sourceAccountName']) {
            $lines[] = sprintf('Il manquerait encore %s une fois cette source vidée.', self::euros($advice['uncoveredCents']));
        }

        $lines[] = 'Écris à l\'utilisateur un message court : dis le retard, la date du découvert et propose l\'avance depuis cette source, '
            .'avec son montant, et rappelle qu\'il pourra la rendre dès que le salaire arrivera. '
            .'Tu ne fais que proposer : n\'exécute aucun virement, ne dis jamais qu\'il est fait ou en cours, c\'est l\'utilisateur qui le passe. '
            .'Vérifie les chiffres avec get_treasury_outlook avant de répondre. '
            .'Ne parle ni du score ni de la revue mensuelle.';

        return implode("\n", $lines);
    }

    /** One alert a day, whatever the number of runs: today's alert, in any state, is the answer. */
    private function alreadyAlerted(User $user, \DateTimeImmutable $today): bool
    {
        $proactions = $this->proactionRepository->findForUser($user, $today, $today->modify('+1 day')->modify('-1 second'));

        foreach ($proactions as $proaction) {
            if (self::SUMMARY === $proaction->getSummary()) {
                return true;
            }
        }

        return false;
    }

    private static function euros(int $cents): string
    {
        return number_format($cents / 100, 2, ',', ' ').' €';
    }
}
