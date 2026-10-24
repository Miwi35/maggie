<?php

namespace Maggie\Core\Tests\Web;

use Maggie\Core\Web\WebPageExtractor;
use PHPUnit\Framework\TestCase;

class WebPageExtractorTest extends TestCase
{
    private function extract(string $fixture): array
    {
        return (new WebPageExtractor())->extract(file_get_contents(__DIR__.'/fixtures/'.$fixture));
    }

    public function testARecipePageGivesItsRecipeObjectWithIngredientsAndSteps(): void
    {
        $page = $this->extract('marmiton-boeuf-bourguignon.html');

        self::assertSame('Boeuf bourguignon : la vraie recette | Marmiton', $page['title']);
        self::assertSame(['Recipe', 'BreadcrumbList'], array_column($page['structured'], '@type'));

        $recipe = $page['structured'][0];
        self::assertSame('Boeuf bourguignon : la vraie recette', $recipe['name']);
        self::assertSame('6 personnes', $recipe['recipeYield']);
        self::assertCount(6, $recipe['recipeIngredient']);
        self::assertSame('1,2 kg de boeuf à braiser', $recipe['recipeIngredient'][0]);
        self::assertCount(3, $recipe['recipeInstructions']);
        self::assertSame('Laisser mijoter à feu doux pendant 3 heures.', $recipe['recipeInstructions'][2]['text']);
    }

    public function testTheTextIsTheMainContentWithoutScriptsOrPageChrome(): void
    {
        $text = $this->extract('marmiton-boeuf-bourguignon.html')['text'];

        self::assertStringStartsWith("Boeuf bourguignon : la vraie recette\n", $text);
        self::assertStringContainsString("Ingrédients\n", $text);
        self::assertStringContainsString("1,2 kg de boeuf à braiser\n150 g de lardons\n2 oignons", $text);
        self::assertStringContainsString('Laisser mijoter à feu doux pendant 3 heures.', $text);
        foreach (['tracking', 'Newsletter', 'Mentions légales', 'vous plairont', 'display: none'] as $noise) {
            self::assertStringNotContainsString($noise, $text);
        }
    }

    public function testAnEventPageGivesItsEventObject(): void
    {
        $page = $this->extract('event-concert.html');

        self::assertCount(1, $page['structured']);
        self::assertSame('Event', $page['structured'][0]['@type']);
        self::assertSame('2026-11-14T20:30:00+01:00', $page['structured'][0]['startDate']);
        self::assertSame('Le Liberté', $page['structured'][0]['location']['name']);
        self::assertSame('Concert de jazz au Liberté - Rennes', $page['title']);
        self::assertStringContainsString('Tarif unique : 22 euros.', $page['text']);
    }

    public function testAProductPageKeepsEveryObjectOfAListedBlock(): void
    {
        $page = $this->extract('product-coffee.html');

        self::assertSame(['Organization', 'Product'], array_column($page['structured'], '@type'));
        self::assertSame('14.90', $page['structured'][1]['offers']['price']);
        self::assertStringContainsString('14,90 € - En stock', $page['text']);
    }

    public function testAPageWithoutJsonLdOrTitleStillGivesItsText(): void
    {
        $page = (new WebPageExtractor())->extract('<html><body><h1>Bonjour</h1><p>Un texte.</p></body></html>');

        self::assertSame([], $page['structured']);
        self::assertSame('Bonjour', $page['title']);
        self::assertSame("Bonjour\nUn texte.", $page['text']);
    }

    public function testBrokenHtmlAndBrokenJsonLdDoNotThrow(): void
    {
        $page = (new WebPageExtractor())->extract('<div><p>Ouvert <script type="application/ld+json">{"@type": </script>');

        self::assertSame([], $page['structured']);
        self::assertSame('Ouvert', $page['text']);
    }

    public function testALongTextIsBounded(): void
    {
        $page = (new WebPageExtractor())->extract('<body><p>'.str_repeat('mot ', 20_000).'</p></body>');

        self::assertLessThanOrEqual(WebPageExtractor::MAX_TEXT_CHARS + 4, mb_strlen($page['text']));
        self::assertStringEndsWith('[…]', $page['text']);
    }

    public function testTheStructuredDataIsBounded(): void
    {
        $blocks = '';
        foreach (range(1, 50) as $i) {
            $blocks .= '<script type="application/ld+json">{"@type":"Thing","name":"'.$i.'"}</script>';
        }

        self::assertCount(30, (new WebPageExtractor())->extract('<html><head>'.$blocks.'</head></html>')['structured']);
    }
}
