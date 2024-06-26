package com.blakebr0.extendedcrafting.compat.jei.tablecrafting;

import com.blakebr0.cucumber.helper.ResourceHelper;
import com.blakebr0.cucumber.util.Utils;
import com.blakebr0.extendedcrafting.Tags;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import java.util.List;

@MethodsReturnNonnullByDefault
public class EliteTableCategory implements IRecipeCategory<IRecipeWrapper> {

	public static final String UID = "extendedcrafting:table_crafting_7x7";
	private static final ResourceLocation TEXTURE = ResourceHelper.getResource(Tags.MODID, "textures/jei/elite_crafting.png");

	private final IDrawable background;

	public EliteTableCategory(IGuiHelper helper) {
		this.background = helper.createDrawable(TEXTURE, 0, 0, 126, 159);
	}

	@Override
	public String getUid() {
		return UID;
	}

	@Override
	public String getTitle() {
		return Utils.localize("jei.ec.table_crafting_7x7");
	}

	@Override
	public String getModName() {
		return Tags.MODNAME;
	}

	@Override
	public IDrawable getBackground() {
		return this.background;
	}

	@Override
	public void setRecipe(IRecipeLayout layout, @Nonnull IRecipeWrapper wrapper, IIngredients ingredients) {
		IGuiItemStackGroup stacks = layout.getItemStacks();

		List<List<ItemStack>> inputs = ingredients.getInputs(VanillaTypes.ITEM);
		List<ItemStack> outputs = ingredients.getOutputs(VanillaTypes.ITEM).get(0);

		stacks.init(0, false, 65, 137);
		stacks.set(0, outputs);

		for (int i = 0; i < 7; i++) {
			for (int j = 0; j < 7; j++) {
				int index = 1 + j + (i * 7);
				stacks.init(index, true, j * 18, i * 18);
			}
		}

		if (wrapper instanceof TableShapedWrapper) {
			TableShapedWrapper shaped = (TableShapedWrapper) wrapper;
			
			int stackIndex = 0;
			for (int i = 0; i < shaped.getHeight(); i++) {
				for (int j = 0; j < shaped.getWidth(); j++) {
					int index = 1 + (i * 7) + j;
					
					stacks.set(index, inputs.get(stackIndex));
					
					stackIndex++;
				}
			}
		} else if (wrapper instanceof TableShapelessWrapper) {
			int i = 1;
			for (List<ItemStack> stack : inputs) {
				stacks.set(i, stack);
				i++;
			}
		}

		layout.setRecipeTransferButton(113, 146);
	}
}
