package random.gba.randomizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import fedata.gba.GBAFEChapterData;
import fedata.gba.GBAFEChapterUnitData;
import fedata.gba.GBAFECharacterData;
import fedata.gba.GBAFEClassData;
import fedata.gba.general.WeaponRank;
import fedata.general.FEBase.GameType;
import random.gba.loader.ChapterLoader;
import random.gba.loader.CharacterDataLoader;
import random.gba.loader.ClassDataLoader;
import random.gba.loader.ItemDataLoader;
import random.gba.loader.PaletteLoader;
import random.gba.loader.TextLoader;
import util.DebugPrinter;
import util.FreeSpaceManager;

public class RecruitmentRandomizer {
	
	static final int rngSalt = 911;
	
	public static void randomizeRecruitment(GameType type, 
			CharacterDataLoader characterData, ClassDataLoader classData, ItemDataLoader itemData, ChapterLoader chapterData, PaletteLoader paletteData, TextLoader textData, FreeSpaceManager freeSpace,
			Random rng) {
		
		// Figure out mapping first.
		List<GBAFECharacterData> characterPool = new ArrayList<GBAFECharacterData>(characterData.canonicalPlayableCharacters());
		characterPool.removeIf(character -> (characterData.charactersExcludedFromRandomRecruitment().contains(character)));
		
		Map<Integer, GBAFECharacterData> referenceData = characterPool.stream().map(character -> {
			return character.createCopy(false);
		}).collect(Collectors.toMap(charData -> (charData.getID()), charData -> (charData)));
		
		Map<GBAFECharacterData, GBAFECharacterData> characterMap = new HashMap<GBAFECharacterData, GBAFECharacterData>();
		List<GBAFECharacterData> slotsRemaining = new ArrayList<GBAFECharacterData>(characterPool);
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		// Assign fliers first, since they are restricted in where they can end up.
		// The slots are determined by the character, since we know which characters must be flying normally.
		// The pool is determined by the character's new class (if it was randomized). This pool should always be larger than the number of slots
		// since all fliers are required to randomize into flier classes. There might be other characters that randomized into fliers though.
		// All fliers can promote and demote, so we should be ok here for promotions.
		List<GBAFECharacterData> flierSlotsRemaining = slotsRemaining.stream().filter(character -> (characterData.isFlyingCharacter(character.getID()))).collect(Collectors.toList());
		List<GBAFECharacterData> flierPool = characterPool.stream().filter(character -> (classData.isFlying(character.getClassID()))).collect(Collectors.toList());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigning fliers...");
		List<SlotAssignment> assignedSlots = shuffleCharactersInPool(flierSlotsRemaining, flierPool, characterMap, referenceData, characterData, classData, textData, rng);
		for (SlotAssignment assignment : assignedSlots) {
			slotsRemaining.removeIf(character -> (character.getID() == assignment.slot.getID()));
			characterPool.removeIf(character -> (character.getID() == assignment.fill.getID()));
		}
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		// Prioritize those with melee/ranged requirements too.
		List<GBAFECharacterData> meleeRequiredSlotsRemaining = slotsRemaining.stream().filter(character -> (characterData.characterIDRequiresMelee(character.getID()))).collect(Collectors.toList());
		List<GBAFECharacterData> meleePool = characterPool.stream().filter(character -> (classData.canSupportMelee(character.getClassID()))).collect(Collectors.toList());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigning Required Melee Units...");
		assignedSlots = shuffleCharactersInPool(meleeRequiredSlotsRemaining, meleePool, characterMap, referenceData, characterData, classData, textData, rng);
		for (SlotAssignment assignment : assignedSlots) {
			slotsRemaining.removeIf(character -> (character.getID() == assignment.slot.getID()));
			characterPool.removeIf(character -> (character.getID() == assignment.fill.getID()));
		}
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		List<GBAFECharacterData> rangeRequiredSlotsRemaining = slotsRemaining.stream().filter(character -> (characterData.characterIDRequiresRange(character.getID()))).collect(Collectors.toList());
		List<GBAFECharacterData> rangePool = characterPool.stream().filter(character -> (classData.canSupportRange(character.getClassID()))).collect(Collectors.toList());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigning Required Ranged Units...");
		assignedSlots = shuffleCharactersInPool(rangeRequiredSlotsRemaining, rangePool, characterMap, referenceData, characterData, classData, textData, rng);
		for (SlotAssignment assignment : assignedSlots) {
			slotsRemaining.removeIf(character -> (character.getID() == assignment.slot.getID()));
			characterPool.removeIf(character -> (character.getID() == assignment.fill.getID()));
		}
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		// Prioritize those that require attack next. This generally means lords.
		// Note: these also have to be able to demote.
		List<GBAFECharacterData> attackingSlotsRemaining = slotsRemaining.stream().filter(character -> (characterData.mustAttack(character.getID()))).collect(Collectors.toList());
		List<GBAFECharacterData> attackingPool = characterPool.stream().filter(character -> {
			GBAFEClassData charClass = classData.classForID(character.getClassID());
			// Promoted class taht can demote should check all of their demotion options. Any demotion that can't attack disqualifies the class.
			if (classData.isPromotedClass(charClass.getID()) && classData.canClassDemote(charClass.getID())) {
				for (GBAFEClassData demotedClass : classData.demotionOptions(charClass.getID())) {
					if (classData.canClassAttack(demotedClass.getID()) == false) { return false; }
				}
			}
			return classData.canClassAttack(charClass.getID());
		}).collect(Collectors.toList());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigning Required Attackers...");
		assignedSlots = shuffleCharactersInPool(attackingSlotsRemaining, attackingPool, characterMap, referenceData, characterData, classData, textData, rng);
		for (SlotAssignment assignment : assignedSlots) {
			slotsRemaining.removeIf(character -> (character.getID() == assignment.slot.getID()));
			characterPool.removeIf(character -> (character.getID() == assignment.fill.getID()));
		}
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		// Prioritize those that can't demote into valid classes so they don't get left behind.
		List<GBAFECharacterData> promotedSlotsRemaining = slotsRemaining.stream().filter(character -> (classData.isPromotedClass(character.getClassID()))).collect(Collectors.toList());
		List<GBAFECharacterData> mustBePromotedPool = characterPool.stream().filter(character -> {
			GBAFEClassData charClass = classData.classForID(character.getClassID());
			return !classData.canClassDemote(charClass.getID()) && classData.isPromotedClass(charClass.getID());
		}).collect(Collectors.toList());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigning non-demotable classes...");
		assignedSlots = shuffleCharactersInPool(promotedSlotsRemaining, mustBePromotedPool, characterMap, referenceData, characterData, classData, textData, rng);
		for (SlotAssignment assignment : assignedSlots) {	
			slotsRemaining.removeIf(character -> (character.getID() == assignment.slot.getID()));
			characterPool.removeIf(character -> (character.getID() == assignment.fill.getID()));
		}
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		// Assign everybody else randomly.
		// We do have to make sure characters that can get assigned can promote/demote if necessary.
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigning the remainder of the characters...");
		assignedSlots = shuffleCharactersInPool(slotsRemaining, characterPool, characterMap, referenceData, characterData, classData, textData, rng);
		for (SlotAssignment assignment : assignedSlots) {	
			slotsRemaining.removeIf(character -> (character.getID() == assignment.slot.getID()));
			characterPool.removeIf(character -> (character.getID() == assignment.fill.getID()));
		}
		
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slots Remaining: " + slotsRemaining.size());
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Pool Size: " + characterPool.size());
		
		if (!characterPool.isEmpty()) {
			for (GBAFECharacterData unassigned: characterPool) {
				DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Unassigned: 0x" + Integer.toHexString(unassigned.getID()) + " (" + textData.getStringAtIndex(unassigned.getNameIndex()) + ")");
			}
		}
		
		assert characterPool.isEmpty() : "Unable to satisfy all constraints for random recruitment.";
		
		Map<String, String> textReplacements = new HashMap<String, String>();
		
		// Process every mapped character.
		// The fill should always be reference data, so it will not have changed from earlier substitutions.
		for (GBAFECharacterData slot : characterMap.keySet()) {
			GBAFECharacterData fill = characterMap.get(slot);
			if (fill != null) {
				// Track the text changes before we change anything.
				// Face IDs
				// Some games have multiple portraits per character. Replace all of them (think Eliwood's many faces in FE7).
				if (characterData.multiPortraitsForCharacter(slot.getID()).isEmpty()) {
					textReplacements.put("[LoadFace][0x" + Integer.toHexString(slot.getFaceID()) + "]", "[LoadFace][0x" + Integer.toHexString(fill.getFaceID()) + "]");
				} else {
					for (int faceID : characterData.multiPortraitsForCharacter(slot.getID())) {
						textReplacements.put("[LoadFace][0x" + Integer.toHexString(faceID) + "]", "[LoadFace][0x" + Integer.toHexString(fill.getFaceID()) + "]");
					}
				}
				textReplacements.put(textData.getStringAtIndex(slot.getNameIndex()).trim(), textData.getStringAtIndex(fill.getNameIndex()).trim());
				textReplacements.put(textData.getStringAtIndex(slot.getNameIndex()).toUpperCase().trim(), textData.getStringAtIndex(fill.getNameIndex()).trim()); // Sometimes people yell too. :(
				// TODO: pronouns?
				
				// Apply the change to the data.
				fillSlot(slot, fill, characterData, classData, itemData, chapterData, paletteData, textData, type, rng);
			}
		}
		
		// Commit all of the palettes now.
		paletteData.flushChangeQueue(freeSpace);
		
		// Run through the text and modify portraits and names in text.
		
		// Build tokens for pattern
		String patternString = "(" + patternStringFromReplacements(textReplacements) + ")";
		Pattern pattern = Pattern.compile(patternString);
					
		for (int i = 0; i < textData.getStringCount(); i++) {
			String originalStringWithCodes = textData.getStringAtIndex(i, false);
			
			String workingString = new String(originalStringWithCodes);
			Matcher matcher = pattern.matcher(workingString);
			StringBuffer sb = new StringBuffer();
			while (matcher.find()) {
				String capture = matcher.group(1);
				String replacementKey = textReplacements.get(capture);
				if (replacementKey == null) {
					// Strip out any stuttering.
					String truncated = capture.substring(capture.lastIndexOf('-') + 1);
					replacementKey = textReplacements.get(truncated);
				}
				matcher.appendReplacement(sb, replacementKey);
			}
			
			matcher.appendTail(sb);
			
			textData.setStringAtIndex(i, sb.toString(), true);
		}
	}
	
	private static String patternStringFromReplacements(Map<String, String> replacements) {
		StringBuilder sb = new StringBuilder();
		for (String stringToReplace : replacements.keySet()) {
			boolean isControlCode = stringToReplace.charAt(0) == '[';
			
			if (!isControlCode) { sb.append("\\b[" + stringToReplace.charAt(0) + "-]*"); } // Removes any stuttering (ala "E-E-Eliwood!")
			sb.append(Pattern.compile(stringToReplace.replace("[",  "\\[").replace("]", "\\]"), Pattern.LITERAL));
			if (!isControlCode) { sb.append("\\b"); }
			sb.append('|');
		}
		sb.deleteCharAt(sb.length() - 1);
		return sb.toString();
	}
	
	private static class SlotAssignment {
		GBAFECharacterData slot;
		GBAFECharacterData fill;
		
		private SlotAssignment(GBAFECharacterData slot, GBAFECharacterData fill) {
			this.slot = slot;
			this.fill = fill;
		}
	}
	
	private static List<SlotAssignment> shuffleCharactersInPool(List<GBAFECharacterData> slots, List<GBAFECharacterData> pool, Map<GBAFECharacterData, GBAFECharacterData> characterMap, Map<Integer, GBAFECharacterData> referenceData, 
			CharacterDataLoader charData, ClassDataLoader classData, TextLoader textData, Random rng) {
		List<SlotAssignment> additions = new ArrayList<SlotAssignment>();
		
		List<GBAFECharacterData> femaleSlots = slots.stream().filter(character -> (charData.isFemale(character.getID()))).collect(Collectors.toList());
		List<GBAFECharacterData> femalePool = pool.stream().filter(character -> (charData.isFemale(character.getID()))).collect(Collectors.toList());
		
		List<GBAFECharacterData> maleSlots = slots.stream().filter(character -> (charData.isFemale(character.getID()) == false)).collect(Collectors.toList());
		List<GBAFECharacterData> malePool = pool.stream().filter(character -> (charData.isFemale(character.getID()) == false)).collect(Collectors.toList());
		
		if (femalePool.size() >= femaleSlots.size() && malePool.size() >= maleSlots.size()) {
			additions.addAll(shuffle(femaleSlots, femalePool, characterMap, referenceData, classData, textData, rng));
			additions.addAll(shuffle(maleSlots, malePool, characterMap, referenceData, classData, textData, rng));
			
			return additions;
		} else {
			return shuffle(slots, pool, characterMap, referenceData, classData, textData, rng);
		}
	}
	
	private static List<SlotAssignment> shuffle(List<GBAFECharacterData> slots, List<GBAFECharacterData> pool, Map<GBAFECharacterData, GBAFECharacterData> characterMap, Map<Integer, GBAFECharacterData> referenceData, 
			ClassDataLoader classData, TextLoader textData, Random rng) {
		List<SlotAssignment> additions = new ArrayList<SlotAssignment>();
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Shuffling Slots: " + String.join(", ", slots.stream().map(charData -> (textData.getStringAtIndex(charData.getNameIndex()))).collect(Collectors.toList())));
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Character Pool: " + String.join(", ", pool.stream().map(charData -> (textData.getStringAtIndex(charData.getNameIndex()))).collect(Collectors.toList())));
		
		List<GBAFECharacterData> promotedSlots = slots.stream().filter(slot -> (classData.isPromotedClass(slot.getClassID()))).collect(Collectors.toList());
		List<GBAFECharacterData> cantDemotePool = pool.stream().filter(fill -> (classData.isPromotedClass(fill.getClassID()) == true && classData.canClassDemote(fill.getClassID()) == false)).collect(Collectors.toList());
		
		List<GBAFECharacterData> unpromotedSlots = slots.stream().filter(slot -> (classData.isPromotedClass(slot.getClassID()) == false)).collect(Collectors.toList());
		List<GBAFECharacterData> cantPromotePool = pool.stream().filter(fill -> (classData.isPromotedClass(fill.getClassID()) == false && classData.canClassPromote(fill.getClassID()) == false)).collect(Collectors.toList());
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "\tPromoted: " + String.join(", ", promotedSlots.stream().map(charData -> (textData.getStringAtIndex(charData.getNameIndex()))).collect(Collectors.toList())));
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "\tCan't Demote: " + String.join(", ", cantDemotePool.stream().map(charData -> (textData.getStringAtIndex(charData.getNameIndex()))).collect(Collectors.toList())));
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "\tUnpromoted: " + String.join(", ", unpromotedSlots.stream().map(charData -> (textData.getStringAtIndex(charData.getNameIndex()))).collect(Collectors.toList())));
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "\tCan't Promote: " + String.join(", ", cantPromotePool.stream().map(charData -> (textData.getStringAtIndex(charData.getNameIndex()))).collect(Collectors.toList())));
		
		while (!cantDemotePool.isEmpty()) {
			if (promotedSlots.isEmpty()) { break; }
			// Assign promoted slots with this pool first.
			int slotIndex = rng.nextInt(promotedSlots.size());
			GBAFECharacterData slot = promotedSlots.get(slotIndex);
			int fillIndex = rng.nextInt(cantDemotePool.size());
			GBAFECharacterData fill = cantDemotePool.get(fillIndex);
			
			GBAFECharacterData reference = referenceData.get(fill.getID());
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigned slot 0x" + Integer.toHexString(slot.getID()) + " (" + textData.getStringAtIndex(slot.getNameIndex()) + 
					") to 0x" + Integer.toHexString(reference.getID()) + " (" + textData.getStringAtIndex(reference.getNameIndex()) + ")");
			
			characterMap.put(slot, reference);
			additions.add(new SlotAssignment(slot, reference));
			
			promotedSlots.remove(slotIndex);
			cantDemotePool.remove(fillIndex);
			
			slots.removeIf(currentSlot -> (currentSlot.getID() == slot.getID()));
			pool.removeIf(currentFill -> (currentFill.getID() == fill.getID()));
		}
		
		while (!cantPromotePool.isEmpty()) {
			if (unpromotedSlots.isEmpty()) { break; }
			// Assign demoted slots with this pool first.
			int slotIndex = rng.nextInt(unpromotedSlots.size());
			GBAFECharacterData slot = unpromotedSlots.get(slotIndex);
			int fillIndex = rng.nextInt(cantPromotePool.size());
			GBAFECharacterData fill = cantPromotePool.get(fillIndex);
			
			GBAFECharacterData reference = referenceData.get(fill.getID());
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigned slot 0x" + Integer.toHexString(slot.getID()) + " (" + textData.getStringAtIndex(slot.getNameIndex()) + 
					") to 0x" + Integer.toHexString(reference.getID()) + " (" + textData.getStringAtIndex(reference.getNameIndex()) + ")");
			
			characterMap.put(slot, reference);
			additions.add(new SlotAssignment(slot, reference));
			
			unpromotedSlots.remove(slotIndex);
			cantPromotePool.remove(fillIndex);
			
			slots.removeIf(currentSlot -> (currentSlot.getID() == slot.getID()));
			pool.removeIf(currentFill -> (currentFill.getID() == fill.getID()));
			
			if (slots.isEmpty()) {
				break;
			}
		}
		
		while (!slots.isEmpty() && !pool.isEmpty()) {
			int slotIndex = rng.nextInt(slots.size());
			GBAFECharacterData slot = slots.get(slotIndex);
			int fillIndex = rng.nextInt(pool.size());
			GBAFECharacterData fill = pool.get(fillIndex);
			
			// Shouldn't need to guard this one...
			GBAFECharacterData reference = referenceData.get(fill.getID());
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Assigned slot 0x" + Integer.toHexString(slot.getID()) + " (" + textData.getStringAtIndex(slot.getNameIndex()) + 
					") to 0x" + Integer.toHexString(reference.getID()) + " (" + textData.getStringAtIndex(reference.getNameIndex()) + ")");
			
			characterMap.put(slot, reference);
			additions.add(new SlotAssignment(slot, reference));
			
			pool.remove(fillIndex);
			slots.remove(slotIndex);
		}
		
		return additions;
	}

	private static void fillSlot(GBAFECharacterData slot, GBAFECharacterData fill, CharacterDataLoader characterData, ClassDataLoader classData, ItemDataLoader itemData, ChapterLoader chapterData, PaletteLoader paletteData, TextLoader textData, GameType type, Random rng) {
		// Create copy for reference, since we're about to overwrite the slot data.
		// slot is the target for the changes. All changes should be on slot.
		// fill is the source of all of the changes. Fill should NOT be modified.
		GBAFECharacterData slotReference = slot.createCopy(false);
		
		boolean shouldBePromoted = classData.isPromotedClass(slotReference.getClassID());
		boolean isPromoted = classData.isPromotedClass(fill.getClassID());
		
		GBAFEClassData slotSourceClass = classData.classForID(slotReference.getClassID());
		GBAFEClassData slotSourcePromoted = classData.canClassPromote(slotSourceClass.getID()) ? classData.classForID(slotSourceClass.getTargetPromotionID()) : null;
		
		GBAFEClassData fillSourceClass = classData.classForID(fill.getClassID());
		GBAFEClassData targetClass = null;
		
		DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Filling Slot [" + textData.getStringAtIndex(slotReference.getNameIndex()) + "](" + textData.getStringAtIndex(slotSourceClass.getNameIndex()) + ") with [" +
				textData.getStringAtIndex(fill.getNameIndex()) + "](" + textData.getStringAtIndex(fillSourceClass.getNameIndex()) + ")");
		
		GBAFECharacterData[] linkedSlots = characterData.linkedCharactersForCharacter(slotReference);
		for (GBAFECharacterData linkedSlot : linkedSlots) {
			// First, replace the description, and face
			// The name is unnecessary because there's a text find/replace that we apply later.
			linkedSlot.setDescriptionIndex(fill.getDescriptionIndex());
			linkedSlot.setFaceID(fill.getFaceID());
			
			int targetLevel = linkedSlot.getLevel();
			int sourceLevel = fill.getLevel();
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Slot level: " + Integer.toString(targetLevel) + "\tFill Level: " + Integer.toString(sourceLevel));
			
			// Handle Promotion/Demotion leveling as necessary
			if (shouldBePromoted) { targetLevel += 10; }
			if (isPromoted) { sourceLevel += 10; }
			
			int levelsToAdd = targetLevel - sourceLevel;
			
			int promoAdjustHP = 0;
			int promoAdjustSTR = 0;
			int promoAdjustSKL = 0;
			int promoAdjustSPD = 0;
			int promoAdjustDEF = 0;
			int promoAdjustRES = 0;
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Adjusted Slot level: " + Integer.toString(targetLevel) + "\tAdjusted Fill Level: " + Integer.toString(sourceLevel) + "\tLevels To Add: " + Integer.toString(levelsToAdd));
			
			if (shouldBePromoted && !isPromoted) {
				DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Promoting [" + textData.getStringAtIndex(fill.getNameIndex()) + "]");
				// Promote Fill.
				if (targetClass == null) {
					List<GBAFEClassData> promotionOptions = classData.promotionOptions(fill.getClassID());
					DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Promotion Options: [" + String.join(", ", promotionOptions.stream().map(charClass -> (textData.getStringAtIndex(charClass.getNameIndex()))).collect(Collectors.toList())) + "]");
					if (!promotionOptions.isEmpty()) {
						targetClass = promotionOptions.get(rng.nextInt(promotionOptions.size()));
					} else {
						targetClass = fillSourceClass;
					}
					DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Selected Class: " + (targetClass != null ? textData.getStringAtIndex(targetClass.getNameIndex()) : "None"));
					
					// For some reason, some promoted class seem to have lower bases than their unpromoted variants (FE8 lords are an example). If they are lower, adjust upwards.
					if (targetClass.getBaseHP() < fillSourceClass.getBaseHP()) { promoAdjustHP = fillSourceClass.getBaseHP() - targetClass.getBaseHP() + targetClass.getPromoHP(); }
					if (targetClass.getBaseSTR() < fillSourceClass.getBaseSTR()) { promoAdjustSTR = fillSourceClass.getBaseSTR() - targetClass.getBaseSTR() + targetClass.getPromoSTR(); }
					if (targetClass.getBaseSKL() < fillSourceClass.getBaseSKL()) { promoAdjustSKL = fillSourceClass.getBaseSKL() - targetClass.getBaseSKL() + targetClass.getPromoSKL(); }
					if (targetClass.getBaseSPD() < fillSourceClass.getBaseSPD()) { promoAdjustSPD = fillSourceClass.getBaseSPD() - targetClass.getBaseSPD() + targetClass.getPromoSPD(); }
					if (targetClass.getBaseDEF() < fillSourceClass.getBaseDEF()) { promoAdjustDEF = fillSourceClass.getBaseDEF() - targetClass.getBaseDEF() + targetClass.getPromoDEF(); }
					if (targetClass.getBaseRES() < fillSourceClass.getBaseRES()) { promoAdjustRES = fillSourceClass.getBaseRES() - targetClass.getBaseRES() + targetClass.getPromoRES(); }
				}
				
				setSlotClass(linkedSlot, targetClass, characterData, classData, itemData, textData, chapterData, rng);
			} else if (!shouldBePromoted && isPromoted) {
				DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Demoting [" + textData.getStringAtIndex(fill.getNameIndex()) + "]");
				// Demote Fill.
				if (targetClass == null) {
					List<GBAFEClassData> demotionOptions = classData.demotionOptions(fill.getClassID());
					DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Demotion Options: [" + String.join(", ", demotionOptions.stream().map(charClass -> (textData.getStringAtIndex(charClass.getNameIndex()))).collect(Collectors.toList())) + "]");
					if (!demotionOptions.isEmpty()) {
						targetClass = demotionOptions.get(rng.nextInt(demotionOptions.size()));
					} else {
						targetClass = fillSourceClass;
					}
					DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "Selected Class: " + (targetClass != null ? textData.getStringAtIndex(targetClass.getNameIndex()) : "None"));
					
					// For some reason, some promoted class seem to have lower bases than their unpromoted variants (FE8 lords are an example). If our demoted class has higher bases, adjust downwards
					if (targetClass.getBaseHP() > fillSourceClass.getBaseHP()) { promoAdjustHP = targetClass.getBaseHP() - fillSourceClass.getBaseHP() + fillSourceClass.getPromoHP(); promoAdjustHP *= -1; }
					if (targetClass.getBaseSTR() > fillSourceClass.getBaseSTR()) { promoAdjustSTR = targetClass.getBaseSTR() - fillSourceClass.getBaseSTR() + fillSourceClass.getPromoSTR(); promoAdjustSTR *= -1; }
					if (targetClass.getBaseSKL() > fillSourceClass.getBaseSKL()) { promoAdjustSKL = targetClass.getBaseSKL() - fillSourceClass.getBaseSKL() + fillSourceClass.getPromoSKL(); promoAdjustSKL *= -1; }
					if (targetClass.getBaseSPD() > fillSourceClass.getBaseSPD()) { promoAdjustSPD = targetClass.getBaseSPD() - fillSourceClass.getBaseSPD() + fillSourceClass.getPromoSPD(); promoAdjustSPD *= -1; }
					if (targetClass.getBaseDEF() > fillSourceClass.getBaseDEF()) { promoAdjustDEF = targetClass.getBaseDEF() - fillSourceClass.getBaseDEF() + fillSourceClass.getPromoDEF(); promoAdjustDEF *= -1; }
					if (targetClass.getBaseRES() > fillSourceClass.getBaseRES()) { promoAdjustRES = targetClass.getBaseRES() - fillSourceClass.getBaseRES() + fillSourceClass.getPromoRES(); promoAdjustRES *= -1; }
				}
				
				setSlotClass(linkedSlot, targetClass, characterData, classData, itemData, textData, chapterData, rng);
			} else {
				// Transfer as is.
				if (targetClass == null) {
					targetClass = fillSourceClass;
				}
				DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "No Promotion/Demotion Needed. Class: " + (targetClass != null ? textData.getStringAtIndex(targetClass.getNameIndex()) : "None"));
				setSlotClass(linkedSlot, targetClass, characterData, classData, itemData, textData, chapterData, rng);
			}
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "== Stat Adjustment from Class Bases ==");
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "HP: " + promoAdjustHP);
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "STR: " + promoAdjustSTR);
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "SKL: " + promoAdjustSKL);
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "SPD: " + promoAdjustSPD);
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "DEF: " + promoAdjustDEF);
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "RES: " + promoAdjustRES);
			
			// Adjust bases based on level difference and promotion changes.
			int hpDelta = (int)Math.floor((float)(fill.getHPGrowth() / 100.0) * levelsToAdd) + promoAdjustHP;
			int strDelta = (int)Math.floor((float)(fill.getSTRGrowth() / 100.0) * levelsToAdd) + promoAdjustSTR;
			int sklDelta = (int)Math.floor((float)(fill.getSKLGrowth() / 100.0) * levelsToAdd) + promoAdjustSKL;
			int spdDelta = (int)Math.floor((float)(fill.getSPDGrowth() / 100.0) * levelsToAdd) + promoAdjustSPD;
			int lckDelta = (int)Math.floor((float)(fill.getLCKGrowth() / 100.0) * levelsToAdd);
			int defDelta = (int)Math.floor((float)(fill.getDEFGrowth() / 100.0) * levelsToAdd) + promoAdjustDEF;
			int resDelta = (int)Math.floor((float)(fill.getRESGrowth() / 100.0) * levelsToAdd) + promoAdjustRES;
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "== Base Deltas ==");
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "HP: " + Integer.toString(hpDelta));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "STR: " + Integer.toString(strDelta));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "SKL: " + Integer.toString(sklDelta));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "SPD: " + Integer.toString(spdDelta));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "DEF: " + Integer.toString(defDelta));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "RES: " + Integer.toString(resDelta));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "LCK: " + Integer.toString(lckDelta));
			
			// Clamp the delta to make sure we're not overflowing caps or underflowing to negative.
			// Clamp the minimum so that people aren't force to 0 base stats, but they can go down as far as 25% of their normal bases.
			int newHP = Math.min(targetClass.getMaxHP() - targetClass.getBaseHP(), Math.max(fill.getBaseHP() + hpDelta, -3 * targetClass.getBaseHP() / 4));
			int newSTR = Math.min(targetClass.getMaxSTR() - targetClass.getBaseSTR(), Math.max(fill.getBaseSTR() + strDelta, -3 * targetClass.getBaseSTR() / 4));
			int newSKL = Math.min(targetClass.getMaxSKL() - targetClass.getBaseSKL(), Math.max(fill.getBaseSKL() + sklDelta, -3 * targetClass.getBaseSKL() / 4));
			int newSPD = Math.min(targetClass.getMaxSPD() - targetClass.getBaseSPD(), Math.max(fill.getBaseSPD() + spdDelta, -3 * targetClass.getBaseSPD() / 4));
			int newLCK = Math.min(targetClass.getMaxLCK() - targetClass.getBaseLCK(), Math.max(fill.getBaseLCK() + lckDelta, -3 * targetClass.getBaseLCK() / 4));
			int newDEF = Math.min(targetClass.getMaxDEF() - targetClass.getBaseDEF(), Math.max(fill.getBaseDEF() + defDelta, -3 * targetClass.getBaseDEF() / 4));
			int newRES = Math.min(targetClass.getMaxRES() - targetClass.getBaseRES(), Math.max(fill.getBaseRES() + resDelta, -3 * targetClass.getBaseRES() / 4));
			
			// Add their original bases back into the new value.
			
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "== New Bases ==");
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "HP: " + Integer.toString(fillSourceClass.getBaseHP()) + " + " + Integer.toString(fill.getBaseHP()) + " -> " + Integer.toString(targetClass.getBaseHP()) + " + " + Integer.toString(newHP));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "STR: " + Integer.toString(fillSourceClass.getBaseSTR()) + " + " + Integer.toString(fill.getBaseSTR()) + " -> " + Integer.toString(targetClass.getBaseSTR()) + " + " + Integer.toString(newSTR));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "SKL: " + Integer.toString(fillSourceClass.getBaseSKL()) + " + " + Integer.toString(fill.getBaseSKL()) + " -> " + Integer.toString(targetClass.getBaseSKL()) + " + " + Integer.toString(newSKL));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "SPD: " + Integer.toString(fillSourceClass.getBaseSPD()) + " + " + Integer.toString(fill.getBaseSPD()) + " -> " + Integer.toString(targetClass.getBaseSPD()) + " + " + Integer.toString(newSPD));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "DEF: " + Integer.toString(fillSourceClass.getBaseDEF()) + " + " + Integer.toString(fill.getBaseDEF()) + " -> " + Integer.toString(targetClass.getBaseDEF()) + " + " + Integer.toString(newDEF));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "RES: " + Integer.toString(fillSourceClass.getBaseRES()) + " + " + Integer.toString(fill.getBaseRES()) + " -> " + Integer.toString(targetClass.getBaseRES()) + " + " + Integer.toString(newRES));
			DebugPrinter.log(DebugPrinter.Key.GBA_RANDOM_RECRUITMENT, "LCK: " + Integer.toString(fillSourceClass.getBaseLCK()) + " + " + Integer.toString(fill.getBaseLCK()) + " -> " + Integer.toString(targetClass.getBaseLCK()) + " + " + Integer.toString(newLCK));
			
			linkedSlot.setBaseHP(newHP);
			linkedSlot.setBaseSTR(newSTR);
			linkedSlot.setBaseSKL(newSKL);
			linkedSlot.setBaseSPD(newSPD);
			linkedSlot.setBaseLCK(newLCK);
			linkedSlot.setBaseDEF(newDEF);
			linkedSlot.setBaseRES(newRES);
			
			// Transfer growths.
			linkedSlot.setHPGrowth(fill.getHPGrowth());
			linkedSlot.setSTRGrowth(fill.getSTRGrowth());
			linkedSlot.setSKLGrowth(fill.getSKLGrowth());
			linkedSlot.setSPDGrowth(fill.getSPDGrowth());
			linkedSlot.setLCKGrowth(fill.getLCKGrowth());
			linkedSlot.setDEFGrowth(fill.getDEFGrowth());
			linkedSlot.setRESGrowth(fill.getRESGrowth());
			
			linkedSlot.setConstitution(fill.getConstitution());
			linkedSlot.setAffinityValue(fill.getAffinityValue());
		}
		
		// Update palettes to match class.
		if (type == GameType.FE8) {
			paletteData.adaptFE8CharacterToClass(slot.getID(), fill.getID(), slotReference.getClassID(), targetClass.getID(), false);
		} else {
			// Enqueue the change.
			Integer sourceUnpromoted = null;
			if (!shouldBePromoted) { sourceUnpromoted = slotSourceClass.getID(); }
			Integer sourcePromoted = null;
			if (classData.canClassPromote(slotSourceClass.getID())) { sourcePromoted = slotSourcePromoted.getID(); }
			else if (shouldBePromoted) { sourcePromoted = slotSourceClass.getID(); }
			
			Integer targetUnpromoted = null;
			if (!shouldBePromoted) { targetUnpromoted = targetClass.getID(); }
			Integer targetPromoted = null;
			if (classData.canClassPromote(targetClass.getID())) { targetPromoted = targetClass.getTargetPromotionID(); }
			else if (shouldBePromoted) { targetPromoted = targetClass.getID(); }
			
			paletteData.enqueueChange(slot, fill, sourceUnpromoted, targetUnpromoted, sourcePromoted, targetPromoted);
		}
	}
	
	private static void transferWeaponRanks(GBAFECharacterData target, GBAFEClassData targetClass, ItemDataLoader itemData, Random rng) {
		List<Integer> rankValues = new ArrayList<Integer>();
		rankValues.add(target.getSwordRank());
		rankValues.add(target.getLanceRank());
		rankValues.add(target.getAxeRank());
		rankValues.add(target.getBowRank());
		rankValues.add(target.getAnimaRank());
		rankValues.add(target.getLightRank());
		rankValues.add(target.getDarkRank());
		rankValues.add(target.getStaffRank());
		rankValues.removeIf(rank -> (rank == 0));
		
		if (rankValues.isEmpty()) {
			target.setSwordRank(targetClass.getSwordRank());
			target.setLanceRank(targetClass.getLanceRank());
			target.setAxeRank(targetClass.getAxeRank());
			target.setBowRank(targetClass.getBowRank());
			target.setAnimaRank(targetClass.getAnimaRank());
			target.setLightRank(targetClass.getLightRank());
			target.setDarkRank(targetClass.getDarkRank());
			target.setStaffRank(targetClass.getStaffRank());
			
			return;
		}
		
		if (targetClass.getSwordRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setSwordRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setSwordRank(0); }
		if (targetClass.getLanceRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setLanceRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setLanceRank(0); }
		if (targetClass.getAxeRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setAxeRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setAxeRank(0); }
		if (targetClass.getBowRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setBowRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setBowRank(0); }
		if (targetClass.getAnimaRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setAnimaRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setAnimaRank(0); }
		if (targetClass.getLightRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setLightRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setLightRank(0); }
		if (targetClass.getDarkRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			if (itemData.weaponRankFromValue(randomRankValue) == WeaponRank.E) {
				// Dark magic floors on D. There's no E rank dark magic.
				randomRankValue = itemData.weaponRankValueForRank(WeaponRank.D);
			}
			target.setDarkRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setDarkRank(0); }
		if (targetClass.getStaffRank() > 0) {
			int randomRankValue = rankValues.get(rng.nextInt(rankValues.size()));
			target.setStaffRank(randomRankValue);
			if (rankValues.size() > 1) {
				rankValues.remove((Integer)randomRankValue);
			}
		} else { target.setStaffRank(0); }
	}
	
	private static void setSlotClass(GBAFECharacterData slot, GBAFEClassData targetClass, CharacterDataLoader characterData, ClassDataLoader classData, ItemDataLoader itemData, TextLoader textData, ChapterLoader chapterData, Random rng) {
		
		slot.setClassID(targetClass.getID());
		transferWeaponRanks(slot, targetClass, itemData, rng);
		
		for (GBAFEChapterData chapter : chapterData.allChapters()) {
			for (GBAFEChapterUnitData unit : chapter.allUnits()) {
				if (unit.getCharacterNumber() == slot.getID()) {
					unit.setStartingClass(targetClass.getID());
					
					// Set Inventory.
					ClassRandomizer.validateCharacterInventory(slot, targetClass, unit, characterData.characterIDRequiresRange(slot.getID()), characterData.characterIDRequiresMelee(slot.getID()), classData, itemData, textData, false, rng);
					if (characterData.isThiefCharacterID(slot.getID())) {
						ClassRandomizer.validateFormerThiefInventory(unit, itemData);
					}
					ClassRandomizer.validateSpecialClassInventory(unit, itemData, rng);
				}
			}
		}
	}
}
