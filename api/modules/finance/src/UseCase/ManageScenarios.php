<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Enum\ScenarioStatus;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Repository\ScenarioRepository;
use Maggie\Finance\Service\ScenarioCatalog;
use Maggie\Finance\Service\ScenarioItem;
use Maggie\Finance\Service\ScenarioRepayments;

/**
 * Writes the user's scenarios (MAG-550). A scenario is a selection and an
 * order of existing projects, early repayments, placements and a monthly
 * saving: the projects, loans and placements it refers to are never touched.
 *
 * Exactly one scenario is current at most, and nothing is current by default:
 * the user chooses (the database refuses a second one). An automatic scenario
 * exists only as an offer until it is made current; editing one is refused —
 * duplicate it first.
 */
class ManageScenarios
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ScenarioRepository $scenarioRepository,
        private readonly ProjectRepository $projectRepository,
        private readonly PlacementRepository $placementRepository,
        private readonly ScenarioRepayments $scenarioRepayments,
        private readonly ScenarioCatalog $catalog,
        private readonly ReadPlanningInputs $readPlanningInputs,
        private readonly MeasureIndependenceBaseline $measureIndependenceBaseline,
    ) {
    }

    /**
     * @param array<string, mixed> $fields name (required); projectIds absent = every project in the user's order
     *
     * @throws \DomainException when a field is wrong
     */
    public function create(User $user, array $fields, ScenarioKind $kind, ScenarioStatus $status): Scenario
    {
        $name = $this->name($fields['name'] ?? null);

        $scenario = (new Scenario())
            ->setUser($user)
            ->setName($name)
            ->setKind($kind)
            ->setStatus($status);

        if (!array_key_exists('projectIds', $fields)) {
            $fields['projectIds'] = array_map(static fn ($project): string => (string) $project->getId(), $this->projectRepository->findByUser($user));
        }
        $this->fill($scenario, $user, $fields);

        $this->em->persist($scenario);
        $this->em->flush();

        return $scenario;
    }

    /**
     * Only the fields present change.
     *
     * @param array<string, mixed> $fields
     */
    public function update(User $user, string $id, array $fields): Scenario
    {
        $scenario = $this->ownStored($user, $id);
        if (ScenarioKind::Auto === $scenario->getKind()) {
            throw new \DomainException('An automatic scenario is not edited: duplicate it, then edit the copy.');
        }
        if (array_key_exists('name', $fields)) {
            $scenario->setName($this->name($fields['name']));
        }
        $this->fill($scenario, $user, $fields);
        $this->em->flush();

        return $scenario;
    }

    /** The copy is the user's own, saved, never current. */
    public function duplicate(User $user, string $id, mixed $name): Scenario
    {
        $item = $this->item($user, $id);
        $name = null === $name
            ? mb_substr($item->name, 0, Scenario::MAX_NAME_LENGTH - 9).' (copie)'
            : $this->name($name);

        $copy = (new Scenario())
            ->setUser($user)
            ->setName($name)
            ->setKind(ScenarioKind::User)
            ->setStatus(ScenarioStatus::Saved)
            ->setProjects(self::projectRows($item->projectIds))
            ->setRepayments($item->repayments)
            ->setPlacements(self::placementRows($item->placementIds))
            ->setMonthlySaving($item->monthlySaving);

        $this->em->persist($copy);
        $this->em->flush();

        return $copy;
    }

    /** Deleting the current scenario leaves none current. */
    public function delete(User $user, string $id): null
    {
        $scenario = $this->ownStored($user, $id);
        $this->em->remove($scenario);
        $this->em->flush();

        return null;
    }

    /**
     * The one the Dashboard, the treasury, the home and the date N follow. The
     * previous one is let go first, in its own flush: the database allows a
     * single current scenario per user.
     */
    public function makeCurrent(User $user, string $id): Scenario
    {
        $item = $this->item($user, $id);
        if (null !== $item->stored && $item->current) {
            return $item->stored;
        }

        $previous = $this->scenarioRepository->findCurrent($user);
        if (null !== $previous) {
            $previous->setCurrent(false);
            $this->em->flush();
        }

        $scenario = $item->stored;
        if (null === $scenario) {
            $scenario = (new Scenario())
                ->setUser($user)
                ->setName($item->name)
                ->setKind(ScenarioKind::Auto)
                ->setAutoKey($item->autoKey)
                ->setProjects(self::projectRows($item->projectIds));
            $this->em->persist($scenario);
        }
        $scenario->setStatus(ScenarioStatus::Saved)->setCurrent(true);
        $this->em->flush();

        return $scenario;
    }

    public function clearCurrent(User $user): null
    {
        $current = $this->scenarioRepository->findCurrent($user);
        if (null !== $current) {
            $current->setCurrent(false);
            $this->em->flush();
        }

        return null;
    }

    private function item(User $user, string $id): ScenarioItem
    {
        if (!str_starts_with($id, ScenarioItem::AUTO_PREFIX)) {
            return ScenarioItem::stored($this->scenarioRepository->findOneOfUser($user, $id) ?? throw new ScenarioNotFound($id));
        }

        $inputs = $this->readPlanningInputs->execute($user);
        $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];

        return $this->catalog->find($user, $id, $inputs, $base) ?? throw new ScenarioNotFound($id);
    }

    private function ownStored(User $user, string $id): Scenario
    {
        if (str_starts_with($id, ScenarioItem::AUTO_PREFIX)) {
            $item = $this->item($user, $id);
            if (null === $item->stored) {
                throw new \DomainException('An automatic scenario is an offer until it is made current: duplicate it to change it, or make it current.');
            }

            return $item->stored;
        }

        return $this->scenarioRepository->findOneOfUser($user, $id) ?? throw new ScenarioNotFound($id);
    }

    /**
     * Applies the fields that are present, validated against what the user owns.
     *
     * @param array<string, mixed> $fields
     */
    private function fill(Scenario $scenario, User $user, array $fields): void
    {
        if (array_key_exists('projectIds', $fields)) {
            $scenario->setProjects(self::projectRows($this->projectIds($user, $fields['projectIds'])));
        }
        if (array_key_exists('placementIds', $fields)) {
            $scenario->setPlacements(self::placementRows($this->placementIds($user, $fields['placementIds'])));
        }
        if (array_key_exists('repayments', $fields)) {
            $scenario->setRepayments($this->repayments($user, $fields['repayments']));
        }
        if (array_key_exists('monthlySaving', $fields)) {
            $saving = $fields['monthlySaving'];
            if (null !== $saving && (!\is_int($saving) || $saving < 0)) {
                throw new \DomainException('monthlySaving must be a positive integer of cents, or null to keep the measured capacity.');
            }
            $scenario->setMonthlySaving($saving);
        }
    }

    private function name(mixed $name): string
    {
        if (!\is_string($name) || '' === trim($name)) {
            throw new \DomainException('A scenario needs a name.');
        }
        $name = trim($name);
        if (mb_strlen($name) > Scenario::MAX_NAME_LENGTH) {
            throw new \DomainException(\sprintf('The name takes %d characters at most.', Scenario::MAX_NAME_LENGTH));
        }

        return $name;
    }

    /** @return list<string> */
    private function projectIds(User $user, mixed $ids): array
    {
        $ids = $this->idList($ids, 'projectIds');
        foreach ($ids as $id) {
            if (null === $this->projectRepository->findOneOfUser($user, $id)) {
                throw new \DomainException("Project not found: {$id}");
            }
        }

        return $ids;
    }

    /** @return list<string> */
    private function placementIds(User $user, mixed $ids): array
    {
        $ids = $this->idList($ids, 'placementIds');
        foreach ($ids as $id) {
            if (null === $this->placementRepository->findOneOfUser($user, $id)) {
                throw new \DomainException("Placement not found: {$id}");
            }
        }

        return $ids;
    }

    /** @return list<string> */
    private function idList(mixed $ids, string $field): array
    {
        if (!\is_array($ids) || !array_is_list($ids)) {
            throw new \DomainException("{$field} must be a list of ids.");
        }
        foreach ($ids as $id) {
            if (!\is_string($id) || '' === $id) {
                throw new \DomainException("Each of {$field} must be a non-empty string.");
            }
        }
        $duplicates = array_keys(array_filter(array_count_values($ids), static fn (int $count): bool => $count > 1));
        if ([] !== $duplicates) {
            throw new \DomainException("{$field} names the same id more than once: ".implode(', ', $duplicates).'.');
        }

        return $ids;
    }

    /** @return list<array<string, mixed>> */
    private function repayments(User $user, mixed $raw): array
    {
        if (!\is_array($raw) || !array_is_list($raw)) {
            throw new \DomainException('repayments must be a list of {loanId, month, amountCents|null}.');
        }

        $repayments = [];
        foreach ($raw as $row) {
            if (!\is_array($row)) {
                throw new \DomainException('A repayment is {loanId, month YYYY-MM, amountCents|null}.');
            }
            $repayments[] = $this->scenarioRepayments->normalize($row);
        }
        $ids = array_column($repayments, 'id');
        if (\count(array_unique($ids)) !== \count($ids)) {
            throw new \DomainException('Two repayments share the same id.');
        }

        $inputs = $this->readPlanningInputs->execute($user);
        $this->scenarioRepayments->apply($inputs->loans, $repayments, $inputs->start(), false, true);

        return $repayments;
    }

    /**
     * @param list<string> $projectIds
     *
     * @return list<array{projectId: string, position: int}>
     */
    private static function projectRows(array $projectIds): array
    {
        return array_map(
            static fn (string $projectId, int $position): array => ['projectId' => $projectId, 'position' => $position],
            $projectIds,
            array_keys($projectIds),
        );
    }

    /**
     * @param list<string> $placementIds
     *
     * @return list<array{placementId: string}>
     */
    private static function placementRows(array $placementIds): array
    {
        return array_map(static fn (string $placementId): array => ['placementId' => $placementId], $placementIds);
    }
}
