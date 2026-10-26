<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Service\CiqualClient;
use Maggie\Cookbook\Service\CiqualNutrients;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\PieceWeightCatalog;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

/**
 * Gives the ingredients of the existing recipes what the nutrition per serving needs (MAG-647):
 * a weight per piece for those counted in pieces, the Ciqual macros for those linked to a code
 * without values, and the closest Ciqual food for those linked to nothing. Nothing already
 * there is overwritten; safe to run again.
 */
#[AsCommand(
    name: 'app:cookbook:backfill-recipe-nutrition',
    description: 'Fill the missing piece weights and Ciqual links of the ingredients used by recipes',
)]
final class BackfillRecipeNutritionCommand extends Command
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly PieceWeightCatalog $pieceWeights,
        private readonly CiqualNutrients $ciqualNutrients,
        private readonly CiqualClient $ciqualClient,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'List what would change and write nothing');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        /** @var list<RecipeIngredient> $lines */
        $lines = $this->entityManager->createQueryBuilder()
            ->select('ri', 'i')
            ->from(RecipeIngredient::class, 'ri')
            ->join('ri.ingredient', 'i')
            ->getQuery()
            ->getResult();

        /** @var array<string, array{ingredient: Ingredient, pieces: bool}> $used */
        $used = [];
        foreach ($lines as $line) {
            $ingredient = $line->getIngredient();
            $key = (string) $ingredient->getId();
            $used[$key] ??= ['ingredient' => $ingredient, 'pieces' => false];
            $used[$key]['pieces'] = $used[$key]['pieces'] || Unit::Piece === $line->getUnit();
        }

        $rows = [];
        $byHand = [];
        $changed = 0;
        foreach ($used as ['ingredient' => $ingredient, 'pieces' => $pieces]) {
            $changes = [];

            if ($pieces && null === $ingredient->getPieceWeightGrams()) {
                $grams = $this->pieceWeights->gramsForProduct($ingredient);
                if (null !== $grams) {
                    $changes[] = sprintf('%s g per piece', $grams);
                    if (!$dryRun) {
                        $ingredient->setPieceWeightGrams($grams);
                    }
                } else {
                    $byHand[] = $ingredient->getName();
                }
            }

            if (null === $ingredient->getCiqualAlimCode()) {
                $food = $this->closestFood($ingredient->getName());
                if (null !== $food) {
                    $changes[] = sprintf('Ciqual %s « %s »', $food['alim_code'], $food['alim_name_fr']);
                    if (!$dryRun) {
                        $ingredient->setCiqualAlimCode($food['alim_code']);
                    }
                }
            }

            if (!$dryRun && null !== $ingredient->getCiqualAlimCode() && $this->ciqualNutrients->fillMissing($ingredient)) {
                $changes[] = 'macros from Ciqual';
            } elseif ($dryRun && null !== $ingredient->getCiqualAlimCode() && null === $ingredient->getKcalPer100g()) {
                $changes[] = 'macros from Ciqual';
            }

            if ([] !== $changes) {
                ++$changed;
                $rows[] = [$ingredient->getName(), implode('; ', $changes)];
            }
        }

        if (!$dryRun) {
            $this->entityManager->flush();
        }

        if ([] !== $rows) {
            $io->table(['Ingredient', $dryRun ? 'Would change' : 'Changed'], $rows);
        }
        if ([] !== $byHand) {
            $io->note('No weight per piece known, to set by hand: '.implode(', ', $byHand));
        }
        $io->success(sprintf('%s%d ingredient(s) of %d used by recipes %s.', $dryRun ? '[dry-run] ' : '', $changed, \count($used), $dryRun ? 'to complete' : 'completed'));

        return Command::SUCCESS;
    }

    /** @return array{alim_code: string, alim_name_fr: string}|null */
    private function closestFood(string $name): ?array
    {
        try {
            $foods = $this->ciqualClient->searchFoods($name, 1);
        } catch (\Throwable) {
            return null;
        }

        return $foods[0] ?? null;
    }
}
