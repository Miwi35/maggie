<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Repository\MealGroceryContributionRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\GroceryLineNeeds;
use Maggie\Grocery\Service\Needs;
use Maggie\Grocery\Service\PackagedQuantity;
use Maggie\Grocery\Service\Packaging;

/**
 * What a meal could put on the grocery list, and what the owner chose of it
 * (MAG-295).
 *
 * The preview reads the meal's recipes, one line per product and recipe unit
 * — the quantities of two recipes added up — and
 * says how many packagings that is, and whether the product's stock makes it
 * worth buying: `suggested` is what a client ticks by default. It also says
 * what the list already holds of the product (`onList`), which other meals
 * need it (`otherMeals`) and what adding this meal makes of the line
 * (`withThisMeal`) — computed with the very rule {@see MealGrocerySync} applies
 * when the meal is added (MAG-380). The choice turns the picked ingredients
 * into the lines {@see MealGrocerySync::syncChoice()} reconciles the list with.
 */
class MealGroceryChoice
{
    public function __construct(
        private readonly Packaging $packaging,
        private readonly EntityManagerInterface $em,
        private readonly MealGroceryContributionRepository $contributionRepository,
        private readonly GroceryLineNeeds $lineNeeds,
    ) {
    }

    /**
     * @return array{mealId: string, groceryChoiceMadeAt: ?string, ingredients: list<array<string, mixed>>}
     */
    public function preview(Meal $meal): array
    {
        $ingredients = [];

        foreach ($this->recipeLines($meal) as $line) {
            $ingredient = $line['ingredient'];
            $buy = $this->toBuy($line);

            $ingredients[] = [
                'ingredientId' => (string) $ingredient->getId(),
                'name' => $ingredient->getName(),
                'quantity' => $line['quantity'],
                'unit' => $line['unit']->value,
                'packaging' => null === $ingredient->getPackagingUnit() ? null : [
                    'unit' => $ingredient->getPackagingUnit()->value,
                    'size' => $ingredient->getPackagingSize(),
                    'sizeUnit' => $ingredient->getPackagingSizeUnit()?->value,
                ],
                'packagedQuantity' => null !== $buy['packaged'] ? $buy['quantity'] : null,
                'toBuy' => ['quantity' => $buy['quantity'], 'unit' => $buy['unit']->value],
                // Without a packaging there is nothing to convert: the recipe
                // quantity is exactly what goes on the list.
                'converted' => null === $buy['packaged'] || $buy['packaged']->converted,
                'stockState' => $ingredient->getStockState()->value,
                'suggested' => ProductStockState::InStock !== $ingredient->getStockState(),
                ...$this->plannedFor($meal, $ingredient),
            ];
        }

        return [
            'mealId' => (string) $meal->getId(),
            'groceryChoiceMadeAt' => $meal->getGroceryChoiceMadeAt()?->format('c'),
            'ingredients' => $ingredients,
        ];
    }

    /**
     * The lines the chosen ingredients put on the list.
     *
     * `$chosen` maps an ingredient id to the number of packagings to force, or
     * null for what the recipes ask. The recipes' quantities go in as the
     * meal's need, packagings or not: they are rounded up once, on the whole
     * line, when the list is reconciled. A product without packaging forces a
     * quantity in the first of its recipe units.
     *
     * @param array<string, float|null> $chosen
     *
     * @return list<array{ingredient: Ingredient, unit: Unit, quantity: float}>
     *
     * @throws \DomainException when an ingredient is in none of the meal's recipes
     */
    public function chosenLines(Meal $meal, array $chosen): array
    {
        $byIngredient = [];
        foreach ($this->recipeLines($meal) as $line) {
            $byIngredient[(string) $line['ingredient']->getId()][] = $line;
        }

        $unknown = array_diff(array_map('strval', array_keys($chosen)), array_keys($byIngredient));
        if ([] !== $unknown) {
            throw new \DomainException('Not an ingredient of this meal\'s recipes: '.implode(', ', $unknown).'.');
        }

        $lines = [];

        foreach ($chosen as $ingredientId => $forced) {
            $recipeLines = $byIngredient[(string) $ingredientId];
            $ingredient = $recipeLines[0]['ingredient'];
            $packagingUnit = $ingredient->getPackagingUnit();

            if (null !== $packagingUnit && null !== $forced) {
                // A forced count of packagings (rounded up, at least one) is
                // the meal's need as it stands: the line reads it in packagings.
                $lines[] = [
                    'ingredient' => $ingredient,
                    'unit' => $packagingUnit,
                    'quantity' => (float) $this->packaging->inPackagings($ingredient, $forced, $packagingUnit),
                ];

                continue;
            }

            if (null !== $forced) {
                // One quantity for the product, so one line: in the first unit its recipes use.
                $lines[] = ['ingredient' => $ingredient, 'unit' => $recipeLines[0]['unit'], 'quantity' => $forced];

                continue;
            }

            array_push($lines, ...$recipeLines);
        }

        return $lines;
    }

    /**
     * What the list already holds of the product, who else needs it, and what
     * adding this meal makes of the line.
     *
     * The line is read without this meal's own share, so that previewing a meal
     * already on the list answers the same as previewing it before: the needs
     * on the list are the others', and `withThisMeal` is the line once the meal
     * (re)joins it. Packagings move by the difference the need makes, exactly
     * as {@see GroceryLineNeeds} does when the meal is added.
     *
     * @return array{
     *     onList: ?array{needed: list<array{quantity: float, unit: ?string}>, packages: ?int},
     *     otherMeals: list<array{mealId: string, name: string, date: string, quantity: float, unit: ?string}>,
     *     withThisMeal: array{needed: list<array{quantity: float, unit: ?string}>, packagesTotal: ?int, packagesToAdd: ?int},
     * }
     */
    private function plannedFor(Meal $meal, Ingredient $ingredient): array
    {
        $item = $this->openLineOf($meal, $ingredient);

        $others = [];
        $own = [];
        $otherMeals = [];

        $withOwn = [];

        if (null !== $item) {
            $held = $this->contributionRepository->findByGroceryItem($item);
            $withOwn = $this->lineNeeds->of($item);

            // A packaged line written before needs were kept has them only in its contributions.
            if (null === $item->getNeeded() && null !== $ingredient->getPackagingUnit()) {
                foreach ($held as $contribution) {
                    $withOwn = Needs::add($withOwn, $contribution->getQuantity(), $contribution->getUnit());
                }
            }

            foreach ($held as $contribution) {
                if ((string) $contribution->getMeal()->getId() === (string) $meal->getId()) {
                    $own = Needs::add($own, $contribution->getQuantity(), $contribution->getUnit());

                    continue;
                }

                $other = $contribution->getMeal();
                $otherMeals[] = [
                    'mealId' => (string) $other->getId(),
                    'name' => MealTitle::of($other),
                    'date' => $other->getDate()?->format('Y-m-d') ?? '',
                    'quantity' => $contribution->getQuantity(),
                    'unit' => $contribution->getUnit()?->value,
                ];
            }

            usort($otherMeals, static fn (array $a, array $b) => $a['date'] <=> $b['date']);

            $others = $withOwn;
            foreach ($own as $need) {
                $others = Needs::add($others, -$need['quantity'], null === $need['unit'] ? null : Unit::from($need['unit']));
            }
        }

        $total = $others;
        foreach ($this->recipeLines($meal) as $line) {
            if ((string) $line['ingredient']->getId() === (string) $ingredient->getId()) {
                $total = Needs::add($total, $line['quantity'], $line['unit']);
            }
        }

        $packsOthers = $this->packaging->packsFor($ingredient, $others);
        $packsTotal = $this->packaging->packsFor($ingredient, $total);
        $onListPacks = null;
        if (null !== $item && null !== $packsOthers) {
            // The line as it stands, less the packagings this meal's own share accounts for.
            $packsWithOwn = $this->packaging->packsFor($ingredient, $withOwn);
            $onListPacks = max(0, (int) ($item->getQuantity() ?? 0) - ((int) $packsWithOwn - $packsOthers));
        }

        return [
            'onList' => null === $item || ([] === $others && 0 === ($onListPacks ?? 0)) ? null : [
                'needed' => $others,
                'packages' => $onListPacks,
            ],
            'otherMeals' => $otherMeals,
            'withThisMeal' => [
                'needed' => $total,
                'packagesTotal' => null === $packsTotal ? null : ($onListPacks ?? 0) + $packsTotal - ($packsOthers ?? 0),
                'packagesToAdd' => null === $packsTotal ? null : $packsTotal - ($packsOthers ?? 0),
            ],
        ];
    }

    /** The unchecked line the product has on the meal's owner's list, if any. */
    private function openLineOf(Meal $meal, Ingredient $ingredient): ?GroceryItem
    {
        /** @var GroceryItem|null $item */
        $item = $this->em->getRepository(GroceryItem::class)->createQueryBuilder('i')
            ->join('i.groceryList', 'l')
            ->where('l.user = :user')
            ->andWhere('i.product = :product')
            ->andWhere('i.checked = false')
            ->orderBy('i.position', 'ASC')
            ->setMaxResults(1)
            ->setParameter('user', $meal->getAgenda()->getUser()->getId(), 'ulid')
            ->setParameter('product', $ingredient->getId(), 'ulid')
            ->getQuery()
            ->getOneOrNullResult();

        return $item;
    }

    /**
     * @param array{ingredient: Ingredient, unit: Unit, quantity: float} $line
     *
     * @return array{quantity: float|int, unit: Unit, packaged: ?PackagedQuantity}
     */
    private function toBuy(array $line): array
    {
        $packaged = $this->packaging->packagedQuantity($line['ingredient'], $line['quantity'], $line['unit']);

        if (null === $packaged) {
            return ['quantity' => $line['quantity'], 'unit' => $line['unit'], 'packaged' => null];
        }

        return ['quantity' => $packaged->count, 'unit' => $packaged->unit, 'packaged' => $packaged];
    }

    /**
     * The meal's recipe ingredients, one line per product and unit, in the
     * order the recipes list them.
     *
     * @return list<array{ingredient: Ingredient, unit: Unit, quantity: float}>
     */
    private function recipeLines(Meal $meal): array
    {
        $lines = [];

        foreach ($meal->getRecipes() as $recipe) {
            foreach ($recipe->getIngredients() as $ri) {
                $key = $ri->getIngredient()->getId().':'.$ri->getUnit()->value;

                if (isset($lines[$key])) {
                    $lines[$key]['quantity'] += $ri->getQuantity();

                    continue;
                }

                $lines[$key] = ['ingredient' => $ri->getIngredient(), 'unit' => $ri->getUnit(), 'quantity' => $ri->getQuantity()];
            }
        }

        return array_values($lines);
    }
}
