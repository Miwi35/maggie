<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\CiqualFood;
use Maggie\Cookbook\Entity\CiqualNutrient;
use Maggie\Cookbook\Repository\CiqualFoodRepository;
use Maggie\Cookbook\Repository\CiqualNutrientRepository;
use Symfony\Component\Console\Style\SymfonyStyle;

class CiqualImporter
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly CiqualFoodRepository $foodRepository,
        private readonly CiqualNutrientRepository $nutrientRepository,
        private readonly CiqualValueParser $valueParser,
    ) {
    }

    public function importNutrients(string $constFile, SymfonyStyle $io): int
    {
        $xml = simplexml_load_file($constFile);
        if ($xml === false) {
            throw new \RuntimeException("Failed to parse: {$constFile}");
        }

        $count = 0;
        foreach ($xml->CONST as $node) {
            $constCode = trim((string) $node->const_code);
            if ($this->nutrientRepository->findByConstCode($constCode) !== null) {
                continue;
            }

            $nutrient = new CiqualNutrient();
            $nutrient->setConstCode($constCode);
            $nutrient->setConstNameFr(trim((string) $node->const_nom_fr));

            // Extract unit from name (e.g., "Energie ... (kcal/100 g)" → "kcal/100 g")
            $nameFr = trim((string) $node->const_nom_fr);
            if (preg_match('/\(([^)]+)\)\s*$/', $nameFr, $matches)) {
                $nutrient->setConstUnit(trim($matches[1]));
            }

            $this->em->persist($nutrient);
            $count++;
        }

        $this->em->flush();
        $io->info("Imported {$count} nutrients.");

        return $count;
    }

    public function importFoods(string $alimFile, string $alimGrpFile, SymfonyStyle $io): int
    {
        // Build group name lookup from alim_grp.xml
        $groupNames = $this->buildGroupNameMap($alimGrpFile);

        $xml = simplexml_load_file($alimFile);
        if ($xml === false) {
            throw new \RuntimeException("Failed to parse: {$alimFile}");
        }

        $count = 0;
        $batchSize = 500;
        foreach ($xml->ALIM as $node) {
            $alimCode = trim((string) $node->alim_code);
            if ($this->foodRepository->findByAlimCode($alimCode) !== null) {
                continue;
            }

            $grpCode = trim((string) ($node->alim_grp_code ?? ''));
            $ssgrpCode = trim((string) ($node->alim_ssgrp_code ?? ''));

            $food = new CiqualFood();
            $food->setAlimCode($alimCode);
            $food->setAlimNameFr(trim((string) $node->alim_nom_fr));
            $food->setAlimGroupCode($grpCode !== '' ? $grpCode : null);
            $food->setAlimGroupNameFr($groupNames['grp'][$grpCode] ?? null);
            $food->setAlimSsgroupCode($ssgrpCode !== '' ? $ssgrpCode : null);
            $food->setAlimSsgroupNameFr($groupNames['ssgrp'][$ssgrpCode] ?? null);

            $this->em->persist($food);
            $count++;

            if ($count % $batchSize === 0) {
                $this->em->flush();
                $io->info("Flushed {$count} foods...");
            }
        }

        $this->em->flush();
        $io->info("Imported {$count} foods.");

        return $count;
    }

    public function importCompositions(string $compoFile, SymfonyStyle $io): int
    {
        $conn = $this->em->getConnection();

        // Build ID lookup maps (alimCode → uuid string, constCode → uuid string)
        $foodIdMap = $this->buildFoodIdMap();
        $nutrientIdMap = $this->buildNutrientIdMap();

        $count = 0;
        $batchSize = 2000;
        $batch = [];

        $reader = new \XMLReader();
        if (!$reader->open($compoFile)) {
            throw new \RuntimeException("Failed to open: {$compoFile}");
        }

        $sql = 'INSERT INTO ciqual_food_nutrient (id, food_id, nutrient_id, value, confidence_code, raw_value) VALUES ';

        while ($reader->read()) {
            if ($reader->nodeType !== \XMLReader::ELEMENT || $reader->localName !== 'COMPO') {
                continue;
            }

            $node = new \SimpleXMLElement($reader->readOuterXml());

            $alimCode = trim((string) $node->alim_code);
            $constCode = trim((string) $node->const_code);
            $rawValue = trim((string) ($node->teneur ?? ''));
            $confidenceCode = trim((string) ($node->code_confiance ?? ''));

            $foodId = $foodIdMap[$alimCode] ?? null;
            $nutrientId = $nutrientIdMap[$constCode] ?? null;

            if ($foodId === null || $nutrientId === null) {
                continue;
            }

            $parsedValue = $rawValue !== '' ? $this->valueParser->parse($rawValue) : null;
            $ulid = new \Symfony\Component\Uid\Ulid();

            $batch[] = sprintf(
                "('%s', '%s', '%s', %s, %s, %s)",
                $ulid->toRfc4122(),
                $foodId,
                $nutrientId,
                $parsedValue !== null ? $parsedValue : 'NULL',
                $confidenceCode !== '' ? $conn->quote($confidenceCode) : 'NULL',
                $rawValue !== '' ? $conn->quote($rawValue) : 'NULL',
            );
            $count++;

            if ($count % $batchSize === 0) {
                $conn->executeStatement($sql . implode(',', $batch));
                $batch = [];
                $io->info("Inserted {$count} compositions...");
            }

            unset($node);
        }

        if ($batch !== []) {
            $conn->executeStatement($sql . implode(',', $batch));
        }

        $reader->close();
        $io->info("Imported {$count} compositions.");

        return $count;
    }

    public function truncateAll(): void
    {
        $connection = $this->em->getConnection();
        $connection->executeStatement('DELETE FROM ciqual_food_nutrient');
        $connection->executeStatement('DELETE FROM ciqual_food');
        $connection->executeStatement('DELETE FROM ciqual_nutrient');
    }

    /** @return array{grp: array<string, string>, ssgrp: array<string, string>} */
    private function buildGroupNameMap(string $alimGrpFile): array
    {
        $map = ['grp' => [], 'ssgrp' => []];

        if (!file_exists($alimGrpFile)) {
            return $map;
        }

        $xml = simplexml_load_file($alimGrpFile);
        if ($xml === false) {
            return $map;
        }

        foreach ($xml->ALIM_GRP as $node) {
            $grpCode = trim((string) $node->alim_grp_code);
            $grpName = trim((string) $node->alim_grp_nom_fr);
            $ssgrpCode = trim((string) $node->alim_ssgrp_code);
            $ssgrpName = trim((string) $node->alim_ssgrp_nom_fr);

            if ($grpCode !== '' && $grpName !== '' && !isset($map['grp'][$grpCode])) {
                $map['grp'][$grpCode] = $grpName;
            }
            if ($ssgrpCode !== '' && $ssgrpName !== '' && $ssgrpName !== '-') {
                $map['ssgrp'][$ssgrpCode] = $ssgrpName;
            }
        }

        return $map;
    }

    /** @return array<string, string> */
    private function buildFoodIdMap(): array
    {
        $rows = $this->em->getConnection()->fetchAllAssociative(
            'SELECT alim_code, id FROM ciqual_food',
        );
        $map = [];
        foreach ($rows as $row) {
            $map[$row['alim_code']] = $row['id'];
        }

        return $map;
    }

    /** @return array<string, string> */
    private function buildNutrientIdMap(): array
    {
        $rows = $this->em->getConnection()->fetchAllAssociative(
            'SELECT const_code, id FROM ciqual_nutrient',
        );
        $map = [];
        foreach ($rows as $row) {
            $map[$row['const_code']] = $row['id'];
        }

        return $map;
    }
}
