<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Exception\InvalidFoodIntakeException;
use Maggie\Health\Mcp\FoodIntakeJson;
use Maggie\Health\Message\CreateFoodIntakeCommand;
use Maggie\Health\Message\DeleteFoodIntakeCommand;
use Maggie\Health\Message\UpdateFoodIntakeCommand;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\Service\DailyBalanceReader;
use Maggie\Health\Service\DailyEnergyReader;
use Maggie\Health\Service\FoodIntakeRules;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The user's food journal (MAG-748). « J'ai mangé une salade César à midi »
 * is a create with label, eatenAt today at 12:00 and the figures Maggie
 * gives; « j'ai mangé le déjeuner prévu » is a create with the meal's id. A
 * call that lacks a field is refused with `current`, the journal as it is:
 * nothing is completed.
 */
#[McpTool(name: 'manage_food_intakes', description: 'An intake is what the owner ate; a meal planned on a day is manage_meals, eaten with create and its mealId. Actions: list, create, update, delete. list needs date (YYYY-MM-DD): {intakes, plannedMeals (not yet eaten)}. create, one of: mealId alone (eats the planned meal, eatenAt defaults to now); else eatenAt (ISO 8601 with offset, required) + label + kcal (0-10000), optional proteinG, carbsG, fatG (grams), quantityGrams; ciqualCode (search_ciqual_foods) + eatenAt + quantityGrams, kcal and macros from Ciqual unless given. update, delete need foodIntakeId; update changes only fields given, nothing recomputed; deleting a meal\'s intake makes the meal planned again. Returns {success, intake}. Refusal: {error, field?, current?}, nothing changes.')]
final class ManageFoodIntakesTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly MessageBusInterface $bus,
        private readonly FoodIntakeRepository $intakes,
        private readonly DailyBalanceReader $balance,
        private readonly FoodIntakeRules $rules,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(
        #[Schema(enum: ['list', 'create', 'update', 'delete'])]
        string $action,
        ?string $date = null,
        ?string $foodIntakeId = null,
        ?string $mealId = null,
        ?string $eatenAt = null,
        ?string $label = null,
        ?string $ciqualCode = null,
        ?float $quantityGrams = null,
        ?int $kcal = null,
        ?float $proteinG = null,
        ?float $carbsG = null,
        ?float $fatG = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($date),
                'create' => $this->create($mealId, $eatenAt, $label, $ciqualCode, $quantityGrams, $kcal, $proteinG, $carbsG, $fatG),
                'update' => $this->update($foodIntakeId, $eatenAt, $label, $quantityGrams, $kcal, $proteinG, $carbsG, $fatG),
                'delete' => $this->delete($foodIntakeId),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update or delete."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (InvalidFoodIntakeException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
        }
    }

    private function list(?string $date): string
    {
        if (null === $date) {
            return self::json(['error' => 'date is required for list, as YYYY-MM-DD.', 'field' => 'date']);
        }

        $user = $this->userContext->requireUser();
        try {
            return self::json($this->journal($user, $date));
        } catch (\InvalidArgumentException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => 'date']);
        }
    }

    private function create(?string $mealId, ?string $eatenAt, ?string $label, ?string $ciqualCode, ?float $quantityGrams, ?int $kcal, ?float $proteinG, ?float $carbsG, ?float $fatG): string
    {
        $user = $this->userContext->requireUser();

        try {
            $intake = $this->bus->dispatch(new CreateFoodIntakeCommand(
                userId: (string) $user->getId(),
                mealId: $mealId,
                eatenAt: $eatenAt,
                label: $label,
                ciqualCode: $ciqualCode,
                quantityGrams: $quantityGrams,
                kcal: $kcal,
                proteinG: $proteinG,
                carbsG: $carbsG,
                fatG: $fatG,
                source: 'conversation',
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e, $this->dayJournal($user, $eatenAt));
        }

        return self::json(['success' => true, 'intake' => FoodIntakeJson::of($intake)]);
    }

    private function update(?string $foodIntakeId, ?string $eatenAt, ?string $label, ?float $quantityGrams, ?int $kcal, ?float $proteinG, ?float $carbsG, ?float $fatG): string
    {
        if (null === $foodIntakeId) {
            return self::json(['error' => 'foodIntakeId is required for update.', 'field' => 'foodIntakeId']);
        }

        $user = $this->userContext->requireUser();
        $before = $this->intakes->findOneOfUser($user, $foodIntakeId);
        if (null === $before) {
            return self::json(['error' => "Food intake not found: {$foodIntakeId}"]);
        }
        // Read before the handler touches the entity: the state it is refused against.
        $current = FoodIntakeJson::of($before);

        $given = [$eatenAt, $label, $quantityGrams, $kcal, $proteinG, $carbsG, $fatG];
        if ([] === array_filter($given, static fn (mixed $value): bool => null !== $value)) {
            return self::json(['error' => 'Nothing to change: give at least one field to update.', 'current' => $current]);
        }

        try {
            $intake = $this->bus->dispatch(new UpdateFoodIntakeCommand(
                userId: (string) $user->getId(),
                foodIntakeId: $foodIntakeId,
                eatenAt: $eatenAt,
                label: $label,
                quantityGrams: $quantityGrams,
                kcal: $kcal,
                proteinG: $proteinG,
                carbsG: $carbsG,
                fatG: $fatG,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e, $current);
        }

        return self::json(['success' => true, 'intake' => FoodIntakeJson::of($intake)]);
    }

    private function delete(?string $foodIntakeId): string
    {
        if (null === $foodIntakeId) {
            return self::json(['error' => 'foodIntakeId is required for delete.', 'field' => 'foodIntakeId']);
        }

        try {
            $this->bus->dispatch(new DeleteFoodIntakeCommand(
                userId: (string) $this->userContext->requireUser()->getId(),
                foodIntakeId: $foodIntakeId,
            ));
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true]);
    }

    /** @return array{intakes: list<array<string, mixed>>, plannedMeals: list<array<string, mixed>>} */
    private function journal(User $user, string $date): array
    {
        $day = DailyEnergyReader::day('date', $date);

        return [
            'intakes' => FoodIntakeJson::list($this->intakes->findInRange($user, $day, $day)),
            'plannedMeals' => array_map(FoodIntakeJson::planned(...), $this->balance->plannedMeals($user, $date)),
        ];
    }

    /**
     * The journal of the day a refused create was for: the day of its eatenAt,
     * or today when it has none (a meal's « Mangé »). Null when eatenAt is not
     * a moment.
     *
     * @return array<string, mixed>|null
     */
    private function dayJournal(User $user, ?string $eatenAt): ?array
    {
        try {
            $moment = null === $eatenAt ? $this->clock->now() : $this->rules->eatenAt($eatenAt);
        } catch (InvalidFoodIntakeException) {
            return null;
        }

        return $this->journal($user, $this->rules->dayOf($moment)->format('Y-m-d'));
    }

    /**
     * A domain refusal is Maggie's to read; anything else is ours.
     *
     * @param array<string, mixed>|null $current
     */
    private static function refusal(HandlerFailedException $e, ?array $current = null): string
    {
        $cause = $e->getPrevious() ?? $e;
        if (!$cause instanceof \DomainException) {
            throw $e;
        }

        $data = ['error' => $cause->getMessage()];
        if ($cause instanceof InvalidFoodIntakeException && null !== $cause->field) {
            $data['field'] = $cause->field;
        }
        if (null !== $current) {
            $data['current'] = $current;
        }

        return self::json($data);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
