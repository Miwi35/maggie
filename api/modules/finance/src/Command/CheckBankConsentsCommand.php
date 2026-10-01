<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'app:finance:check-consents',
    description: 'Warn the owners whose bank consent is about to expire',
)]
class CheckBankConsentsCommand extends Command
{
    /** How long before the expiry the owner is warned: enough to reconnect at leisure. */
    public const WARNING_DAYS = 7;

    public function __construct(
        private readonly BankConnectionRepository $connectionRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly MessageBusInterface $messageBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $now = new \DateTimeImmutable();
        $created = 0;

        $connections = $this->connectionRepository->findActiveExpiringBy(
            $now->modify(sprintf('+%d days', self::WARNING_DAYS)),
        );

        foreach ($connections as $connection) {
            $expiresAt = $connection->getConsentExpiresAt();
            if (null === $expiresAt) {
                continue;
            }

            // The link points at the bank page, naming the connection so that
            // each one is warned about once: anything sent since the warning
            // window opened belongs to the current consent, and a renewed
            // consent opens a window months later.
            $iri = '/finance/banks?connection='.$connection->getId();
            $windowStart = $expiresAt->modify(sprintf('-%d days', self::WARNING_DAYS));

            if ($this->notificationRepository->existsSince(NotificationType::ConsentExpiring, $iri, $windowStart)) {
                continue;
            }

            $this->messageBus->dispatch(new CreateNotificationCommand(
                type: NotificationType::ConsentExpiring->value,
                title: $this->title($connection, $expiresAt, $now),
                body: 'Reconnectez la banque pour que Maggie continue de synchroniser vos comptes.',
                relatedEntityIri: $iri,
                userId: (string) $connection->getUser()->getId(),
            ));

            ++$created;
            $io->writeln(sprintf('  Warned about %s (expires %s)', $connection->getBankName(), $expiresAt->format('Y-m-d')));
        }

        if (0 === $created) {
            $io->info('No bank consent to warn about.');
        } else {
            $io->success(sprintf('Created %d consent notification(s).', $created));
        }

        return Command::SUCCESS;
    }

    private function title(BankConnection $connection, \DateTimeImmutable $expiresAt, \DateTimeImmutable $now): string
    {
        $bank = $connection->getBankName();

        if ($expiresAt <= $now) {
            return sprintf("L'accès à %s a expiré — reconnectez la banque", $bank);
        }

        $days = $connection->daysBeforeExpiry($now) ?? 0;

        return match (true) {
            0 === $days => sprintf("L'accès à %s expire aujourd'hui — reconnectez la banque", $bank),
            1 === $days => sprintf("L'accès à %s expire demain — reconnectez la banque", $bank),
            default => sprintf("L'accès à %s expire dans %d jours — reconnectez la banque", $bank, $days),
        };
    }
}
