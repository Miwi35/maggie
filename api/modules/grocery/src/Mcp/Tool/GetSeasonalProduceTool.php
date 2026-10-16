<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Service\SeasonalityCatalog;
use Maggie\Grocery\Service\SeasonCalendar;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_seasonal_produce', description: 'List the fruits and vegetables in season in metropolitan France for a month (1 = January … 12 = December; default: the current month in the user\'s timezone), from the seasonality calendar, and the user\'s own products in season that month. Use it to suggest seasonal meals or to say what is in season. To change the season of one of the user\'s products, use manage_products (seasonMonths).')]
class GetSeasonalProduceTool
{
    private const MONTH_NAMES = [1 => 'janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];

    public function __construct(
        private readonly SeasonalityCatalog $catalog,
        private readonly SeasonCalendar $seasonCalendar,
        private readonly ProductRepository $productRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $month = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $month && ($month < 1 || $month > 12)) {
            return json_encode(['error' => "month must be from 1 (January) to 12 (December), not {$month}."], JSON_THROW_ON_ERROR);
        }

        $month ??= $this->seasonCalendar->currentMonth($user);

        $products = array_values(array_filter(
            $this->productRepository->findByUser($user),
            static fn (Product $product): bool => true === $product->isInSeasonIn($month),
        ));

        return json_encode([
            'month' => $month,
            'monthName' => self::MONTH_NAMES[$month],
            'region' => 'France métropolitaine',
            'produce' => array_map(
                static fn (array $entry): array => ['name' => $entry['name'], 'kind' => $entry['kind']],
                $this->catalog->inSeasonIn($month),
            ),
            'userProducts' => array_map(static fn (Product $product): array => [
                'id' => (string) $product->getId(),
                'name' => $product->getName(),
                'category' => $product->getCategory()->value,
                'seasonMonths' => $product->getSeasonMonths(),
            ], $products),
        ], JSON_THROW_ON_ERROR);
    }
}
