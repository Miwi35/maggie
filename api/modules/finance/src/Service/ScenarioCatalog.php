<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\ScenarioRepository;

/**
 * The scenarios the user can see: the ones they stored, oldest first, then the
 * automatic ones that are not stored (MAG-550). An automatic scenario that was
 * made current is a stored one and takes the place of its offer; deleting it
 * brings the offer back.
 */
final class ScenarioCatalog
{
    public function __construct(
        private readonly ScenarioRepository $repository,
        private readonly AutoScenarios $autoScenarios,
    ) {
    }

    /** @return list<ScenarioItem> */
    public function all(User $user, PlanningInputs $inputs, IndependenceBase $base): array
    {
        $items = array_map(ScenarioItem::stored(...), $this->repository->findByUser($user));

        $taken = array_filter(array_map(static fn (ScenarioItem $item): ?string => $item->autoKey, $items));
        if (\count($taken) < \count(AutoScenarios::NAMES)) {
            foreach ($this->autoScenarios->specs($inputs, $base) as $key => $spec) {
                if (!\in_array($key, $taken, true)) {
                    $items[] = ScenarioItem::automatic($key, $spec);
                }
            }
        }

        return $items;
    }

    /** @param string $id a stored scenario's id, or `auto-<key>` */
    public function find(User $user, string $id, PlanningInputs $inputs, IndependenceBase $base): ?ScenarioItem
    {
        if (str_starts_with($id, ScenarioItem::AUTO_PREFIX)) {
            $key = substr($id, \strlen(ScenarioItem::AUTO_PREFIX));
            if (!isset(AutoScenarios::NAMES[$key])) {
                return null;
            }

            $stored = $this->repository->findOneByAutoKey($user, $key);
            if (null !== $stored) {
                return ScenarioItem::stored($stored);
            }

            return ScenarioItem::automatic($key, $this->autoScenarios->specs($inputs, $base)[$key]);
        }

        $scenario = $this->repository->findOneOfUser($user, $id);

        return null === $scenario ? null : ScenarioItem::stored($scenario);
    }
}
