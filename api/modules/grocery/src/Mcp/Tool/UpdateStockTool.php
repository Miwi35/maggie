<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\ProductRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'update_stock', description: 'Say what is left of a product at home: state is in_stock, low (nearly out) or out. Use it when the user says they are nearly out of something or have run out — not add_grocery_item. product is a product id or its name. When the product is set to restock by itself (autoRestock with a restockQuantity), its restock quantity is put on the grocery list for you and the answer says what was added; otherwise nothing is added and the answer says why. The answer also lists the meals of the next 7 days whose recipes use the product: tell the user about them.')]
class UpdateStockTool
{
    private const MEAL_WINDOW_DAYS = 7;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly ProductRepository $productRepository,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly MealRepository $mealRepository,
    ) {
    }

    public function __invoke(string $product, string $state): string
    {
        try {
            $user = $this->userContext->requireUser();
            $stockState = Product::parseStockState($state);
            $found = $this->resolveProduct($user, $product);

            $linesBefore = $this->openLineQuantities($user, $found);

            $this->bus->dispatch(new UpdateProductCommand(
                productId: (string) $found->getId(),
                stockState: $stockState->value,
            ));

            return json_encode([
                'success' => true,
                'product' => ['id' => (string) $found->getId(), 'name' => $found->getName()],
                'stockState' => $stockState->value,
                'restock' => $this->restock($user, $found, $stockState, $linesBefore),
                'plannedMeals' => $this->plannedMeals($user, $found),
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    /** @throws \DomainException when nothing, or more than one product, matches */
    private function resolveProduct(User $user, string $reference): Product
    {
        $reference = trim($reference);
        if ('' === $reference) {
            throw new \DomainException('Which product? Pass its name or its id.');
        }

        if (Ulid::isValid($reference)) {
            $byId = $this->productRepository->find($reference);
            if (null !== $byId && (string) $byId->getUser()->getId() === (string) $user->getId()) {
                return $byId;
            }
        }

        $candidates = $this->productRepository->searchByName($user, $reference);

        foreach ($candidates as $candidate) {
            if (mb_strtolower($candidate->getName()) === mb_strtolower($reference)) {
                return $candidate;
            }
        }

        if (1 === \count($candidates)) {
            return $candidates[0];
        }

        if ([] === $candidates) {
            throw new \DomainException("Product not found: {$reference}. Use search_products to look for it.");
        }

        throw new \DomainException(sprintf('Several products match "%s": %s. Ask which one, or pass its id.', $reference, implode(', ', array_map(static fn (Product $p) => $p->getName(), $candidates))));
    }

    /**
     * The restock itself is the consequence of the product running low, not
     * of this tool: the answer says what the list gained.
     *
     * @param array<string, float> $linesBefore quantity of each open line of the product before the change
     *
     * @return array{added: bool, reason?: string, quantity?: int, unit?: string|null, lineQuantity?: float|null}
     */
    private function restock(User $user, Product $product, ProductStockState $state, array $linesBefore): array
    {
        if (ProductStockState::InStock === $state) {
            return ['added' => false, 'reason' => 'The product is in stock: nothing to buy.'];
        }
        if (!$product->isAutoRestock()) {
            return ['added' => false, 'reason' => 'Automatic restock is off for this product: nothing was added to the list.'];
        }
        $quantity = $product->getRestockQuantity();
        if (null === $quantity || $quantity <= 0) {
            return ['added' => false, 'reason' => 'This product has no restock quantity: nothing was added to the list.'];
        }

        $line = null;
        foreach ($this->openLines($user, $product) as $candidate) {
            if (($candidate->getQuantity() ?? 0.0) > ($linesBefore[(string) $candidate->getId()] ?? -1.0)) {
                $line = $candidate;
            }
        }
        if (null === $line) {
            return ['added' => false, 'reason' => 'The product was already low or out: its restock was added when it ran low, nothing more was added to the list.'];
        }

        return [
            'added' => true,
            'quantity' => $quantity,
            'unit' => $product->getPackagingUnit()?->value,
            'lineQuantity' => $line->getQuantity(),
        ];
    }

    /** @return array<string, float> a line with no quantity counts as 0, so that adding to it is still a gain */
    private function openLineQuantities(User $user, Product $product): array
    {
        $quantities = [];
        foreach ($this->openLines($user, $product) as $line) {
            $quantities[(string) $line->getId()] = $line->getQuantity() ?? 0.0;
        }

        return $quantities;
    }

    /** @return list<GroceryItem> */
    private function openLines(User $user, Product $product): array
    {
        $list = $this->groceryListRepository->findOneBy(['user' => $user]);
        if (null === $list) {
            return [];
        }

        return array_values(array_filter(
            $list->getItems()->toArray(),
            static fn (GroceryItem $item) => !$item->isChecked() && (string) $item->getProduct()?->getId() === (string) $product->getId(),
        ));
    }

    /** @return list<array{date: string, slot: string, recipes: list<string>}> */
    private function plannedMeals(User $user, Product $product): array
    {
        $productId = (string) $product->getId();

        return array_map(static fn (Meal $meal) => [
            'date' => $meal->getDate()?->format('Y-m-d') ?? '',
            'slot' => $meal->getSlot()->value,
            'recipes' => array_values(array_map(
                static fn (Recipe $recipe) => $recipe->getName(),
                array_filter(
                    $meal->getRecipes()->toArray(),
                    static fn (Recipe $recipe) => $recipe->getIngredients()->exists(
                        static fn ($_, RecipeIngredient $line) => (string) $line->getIngredient()->getId() === $productId,
                    ),
                ),
            )),
        ], $this->mealRepository->findUpcomingUsingProduct($user, $product, self::MEAL_WINDOW_DAYS));
    }
}
