<?php

namespace Maggie\Calendar\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Mercure\MercureTopic;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

/**
 * Merges the agendas that share a Google calendar (MAG-148).
 *
 * Production held two "Concerts" agendas on the same `google_calendar_id`, each
 * syncing on its own, so every event existed twice. The oldest agenda is kept —
 * it is the one the clients and the search index already know — the second one's
 * events move into it, the copies of an event Google only has once are dropped,
 * and the duplicate agenda goes.
 *
 * Runs before the migration that adds the unique index, since that index cannot
 * be created while a duplicate is still there. Once the index exists this is a
 * no-op, and it is safe to run again at any time.
 */
#[AsCommand(
    name: 'app:calendar:dedupe-google-agendas',
    description: 'Merge the agendas that share the same Google calendar',
)]
final class DedupeGoogleAgendasCommand extends Command
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly AgendaRepository $agendaRepository,
        private readonly EventRepository $eventRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly HubInterface $hub,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'Report what would be merged and change nothing');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $groups = $this->findDuplicateGroups();

        $io->definitionList(
            ['Agendas' => $this->countAgendas()],
            ['Events' => $this->countEvents()],
            ['Google calendars connected twice or more' => \count($groups)],
        );

        if ([] === $groups) {
            $io->success('No Google calendar is connected twice.');

            return Command::SUCCESS;
        }

        $rows = [];
        foreach ($groups as $group) {
            $agendas = $this->agendasOf($group);
            foreach ($agendas as $index => $agenda) {
                $rows[] = [
                    $group['google_calendar_id'],
                    (string) $agenda->getId(),
                    $agenda->getName(),
                    \count($this->eventRepository->findBy(['agenda' => $agenda])),
                    0 === $index ? 'kept' : 'merged into the first',
                ];
            }
        }
        $io->table(['Google calendar', 'Agenda', 'Name', 'Events', 'Fate'], $rows);

        if ($dryRun) {
            $io->warning('Dry run: nothing was changed.');

            return Command::SUCCESS;
        }

        foreach ($groups as $group) {
            $ids = array_map(fn (Agenda $agenda) => (string) $agenda->getId(), $this->agendasOf($group));
            $keepId = array_shift($ids);

            foreach ($ids as $duplicateId) {
                $this->merge($keepId, $duplicateId, $io);
            }

            // Google settles what the surviving events should say: dropping the
            // sync token asks for every event again, so a copy that had stopped
            // syncing is brought back up to date.
            $keep = $this->agendaRepository->find($keepId);
            if (null === $keep) {
                continue;
            }
            $keep->setGoogleSyncToken(null);
            $this->entityManager->flush();
            $this->messageBus->dispatch(new PullFromGoogleCommand(agendaId: $keepId));
        }

        $io->definitionList(
            ['Agendas left' => $this->countAgendas()],
            ['Events left' => $this->countEvents()],
        );
        $io->success('Merged.');

        return Command::SUCCESS;
    }

    private function merge(string $keepId, string $duplicateId, SymfonyStyle $io): void
    {
        $keep = $this->agendaRepository->find($keepId);
        $duplicate = $this->agendaRepository->find($duplicateId);
        if (null === $keep || null === $duplicate) {
            return;
        }

        $userId = (string) $keep->getUser()->getId();

        /** @var array<string, Event> $keptByGoogleId */
        $keptByGoogleId = [];
        foreach ($this->eventRepository->findBy(['agenda' => $keep]) as $event) {
            $googleEventId = $event->getGoogleEventId();
            if (null !== $googleEventId) {
                $keptByGoogleId[$googleEventId] = $event;
            }
        }

        $toMove = [];
        $toDrop = [];
        foreach ($this->eventRepository->findBy(['agenda' => $duplicate]) as $event) {
            $googleEventId = $event->getGoogleEventId();
            if (null !== $googleEventId && isset($keptByGoogleId[$googleEventId])) {
                $toDrop[] = $event;
            } else {
                $toMove[] = $event;
            }
        }

        // An occurrence that overrides a recurrence points at its parent, and
        // the database cascades a parent's deletion onto it. Moving it while
        // its parent is dropped would delete it too, so it is tied to the copy
        // that stays — or to nothing, and the sync below puts it back.
        foreach ($toMove as $event) {
            $parent = $event->getRecurringEvent();
            if (null === $parent || !\in_array($parent, $toDrop, true)) {
                continue;
            }

            $parentGoogleId = $parent->getGoogleEventId();
            $event->setRecurringEvent(null === $parentGoogleId ? null : ($keptByGoogleId[$parentGoogleId] ?? null));
        }

        foreach ($toMove as $event) {
            $event->setAgenda($keep);
        }
        $this->entityManager->flush();

        foreach ($toMove as $event) {
            $this->messageBus->dispatch(new IndexDocumentCommand(
                entityClass: Event::class,
                entityId: (string) $event->getId(),
            ));
            $this->publish(MercureTopic::collectionFromShortName('Event'), (string) $event->getId(), $userId, $event->toMercurePayload());
        }

        // Dropped one by one rather than left to the agenda's cascade: the
        // duplicate agenda is then empty when it goes, so nothing else can be
        // carried away with it.
        $droppedIds = [];
        foreach ($toDrop as $event) {
            $droppedIds[] = (string) $event->getId();
            $this->entityManager->remove($event);
        }
        $this->entityManager->flush();

        foreach ($droppedIds as $eventId) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: 'events', documentId: $eventId));
            $this->publishDelete(MercureTopic::collectionFromShortName('Event'), $eventId, $userId);
        }

        // Goes through the bus so the duplicate's Google watch channel is
        // stopped — left alive it would keep pushing changes for an agenda
        // that no longer exists. `deleteGoogleCalendar` stays false: the
        // calendar itself belongs to the agenda that is kept.
        $this->messageBus->dispatch(new DeleteAgendaCommand(
            agendaId: $duplicateId,
            deleteGoogleCalendar: false,
        ));
        $this->publishDelete(MercureTopic::collectionFromShortName('Agenda'), $duplicateId, $userId);

        $io->text(sprintf(
            'Agenda %s: %d event(s) moved in, %d duplicate event(s) dropped, agenda %s removed.',
            $keepId,
            \count($toMove),
            \count($droppedIds),
            $duplicateId,
        ));

        // A removed agenda goes back to being a new entity in the unit of work,
        // and the events that pointed at it would be flushed against it again.
        $this->entityManager->clear();
    }

    /**
     * @return list<array{user_id: string, google_calendar_id: string}>
     */
    private function findDuplicateGroups(): array
    {
        /** @var list<array{user_id: string, google_calendar_id: string}> $rows */
        $rows = $this->entityManager->getConnection()->fetchAllAssociative(<<<'SQL'
            SELECT user_id, google_calendar_id
            FROM agenda
            WHERE google_calendar_id IS NOT NULL
            GROUP BY user_id, google_calendar_id
            HAVING COUNT(*) > 1
            ORDER BY google_calendar_id
            SQL);

        return $rows;
    }

    /**
     * @param array{user_id: string, google_calendar_id: string} $group
     *
     * @return list<Agenda>
     */
    private function agendasOf(array $group): array
    {
        /** @var list<Agenda> $agendas */
        $agendas = $this->agendaRepository->createQueryBuilder('a')
            ->where('a.user = :user')
            ->andWhere('a.googleCalendarId = :googleCalendarId')
            ->setParameter('user', Ulid::fromString($group['user_id']), 'ulid')
            ->setParameter('googleCalendarId', $group['google_calendar_id'])
            // ULIDs sort by creation time: the oldest agenda comes first, and
            // it is the one that is kept.
            ->orderBy('a.id', 'ASC')
            ->getQuery()
            ->getResult();

        return $agendas;
    }

    private function countAgendas(): int
    {
        return (int) $this->entityManager->getConnection()->fetchOne('SELECT COUNT(*) FROM agenda');
    }

    private function countEvents(): int
    {
        return (int) $this->entityManager->getConnection()->fetchOne('SELECT COUNT(*) FROM event');
    }

    /** @param array<string, mixed> $payload */
    private function publish(string $collectionTopic, string $id, string $userId, array $payload): void
    {
        $iri = MercureTopic::item($collectionTopic, $id);
        $this->hub->publish(new Update(
            topics: [MercureTopic::scoped($userId, $iri)],
            data: json_encode(['@id' => $iri] + $payload, JSON_THROW_ON_ERROR),
            private: true,
        ));
    }

    private function publishDelete(string $collectionTopic, string $id, string $userId): void
    {
        $iri = MercureTopic::item($collectionTopic, $id);
        $this->hub->publish(new Update(
            topics: [MercureTopic::scoped($userId, $iri)],
            data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
            private: true,
        ));
    }
}
