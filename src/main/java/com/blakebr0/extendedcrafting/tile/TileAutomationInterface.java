package com.blakebr0.extendedcrafting.tile;

import com.blakebr0.cucumber.energy.EnergyStorageCustom;
import com.blakebr0.cucumber.helper.StackHelper;
import com.blakebr0.cucumber.util.VanillaPacketDispatcher;
import com.blakebr0.extendedcrafting.config.ModConfig;
import com.blakebr0.extendedcrafting.crafting.endercrafter.EnderCrafterRecipeManager;
import com.blakebr0.extendedcrafting.crafting.table.TableCrafting;
import com.blakebr0.extendedcrafting.crafting.table.TableRecipeManager;
import com.blakebr0.extendedcrafting.lib.EmptyContainer;
import com.blakebr0.extendedcrafting.lib.FakeRecipeHandler;
import com.blakebr0.extendedcrafting.lib.IExtendedTable;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

// FIXME please
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class TileAutomationInterface extends TileEntity implements ITickable, ISidedInventory {

	private final ItemStackHandler inventory = new StackHandler();
	private final ItemStackHandler recipe = new FakeRecipeHandler();
	private final EnergyStorageCustom energy = new EnergyStorageCustom(ModConfig.confInterfaceRFCapacity);
	private int oldEnergy;
	private ItemStack result = ItemStack.EMPTY;
	private boolean hasRecipe = false;
	private int recipeSize;
	private int autoInsert = -1;
	private int autoExtract = -1;
	private boolean autoEject = false;
	private boolean smartInsert = true;
	private int ticks = 0;

	@Override
	public void update() {
		boolean mark = false;
		this.ticks++;
		if (!this.getWorld().isRemote) {
			ItemStack input = this.getInventory().getStackInSlot(0);
			ItemStack output = this.getInventory().getStackInSlot(1);
			boolean hasTable = this.hasTable();

			if (!input.isEmpty() && this.getEnergy().getEnergyStored() >= ModConfig.confInterfaceRFRate) {
				this.handleInput(input, hasTable && this.hasRecipe());
			}

			if (hasTable && this.hasRecipe() && this.getEnergy().getEnergyStored() >= ModConfig.confInterfaceRFRate && this.ticks % 10 == 0) {
				this.handleOutput(output);
			}

			boolean scheduledTransfer = this.getEnergy().getEnergyStored() >= ModConfig.confInterfaceRFRate && this.ticks % 4 == 0;
			if (scheduledTransfer) {
				mark = transfer(input, getInserterFace(), true);
				mark |= transfer(output, getExtractorFace(), false);
			}
		}

		if (this.oldEnergy != this.energy.getEnergyStored()) {
			this.oldEnergy = this.energy.getEnergyStored();
			mark = true;
		}

		if (this.ticks > 100) {
			this.ticks = 0;
		}

		if (mark) {
			this.markDirty();
		}
	}

	/**
	 * @param insert Whether this interface (or the table) is the one being inserted into. i.e. whether we need to extract.
	 * @return {@literal true} if any kind of transfer successfully took place.
	 */
	private boolean transfer(ItemStack stack, @Nullable EnumFacing side, boolean insert) {
		if (side == null || (!insert && stack.isEmpty())) {
			return insert;
		}
		TileEntity tile = getWorld().getTileEntity(pos.offset(side));
		if (tile == null) {
			return insert;
		}
		IItemHandler cap = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side.getOpposite());
		if (cap == null) {
			return insert;
		}
		for (int slot = 0; slot < cap.getSlots(); slot++) {
			ItemStack s = cap.getStackInSlot(slot);
			if (insert) {
				if (s.isEmpty()) {
					continue;
				}
				ItemStack toInsert = StackHelper.withSize(s.copy(), 1, false);
				if (!this.checkStackSmartly(toInsert) || !(stack.isEmpty() || (StackHelper.canCombineStacks(stack, toInsert)))) {
					continue;
				}
				ItemStack newStack = cap.extractItem(slot, 1, false);
				if(newStack.isEmpty()) {
					continue;
				}
				this.getInventory().insertItem(0, toInsert, false);
			} else {
				ItemStack toInsert = StackHelper.withSize(stack.copy(), 1, false);
				ItemStack newStack = cap.insertItem(slot, toInsert, false);
				if(!newStack.isEmpty()) {
					continue;
				}
				stack.shrink(1);
			}
			this.getEnergy().extractEnergy(ModConfig.confInterfaceRFRate, false);
			return true;
		}
		return false;
	}

	private void handleInput(ItemStack input, boolean canInsert) {
		ItemStack output = this.getInventory().getStackInSlot(1);
		ItemStack toInsert = StackHelper.withSize(input.copy(), 1, false);
		IExtendedTable table = null;
		IInventory matrix = null;
		int slotToPut = -1;

		if (canInsert) {
			table = this.getTable();
			if(table == null) {
				return;
			}

			ItemStackHandler recipe = this.getRecipe();
			matrix = (IInventory) table;

			ItemStack stackToPut = ItemStack.EMPTY;
			for (int i = 0; i < matrix.getSizeInventory(); i++) {
				ItemStack slot = matrix.getStackInSlot(i);
				ItemStack recipeStack = recipe.getStackInSlot(i);
				if (((slot.isEmpty() || StackHelper.areStacksEqual(input, slot)) && StackHelper.areStacksEqual(input, recipeStack))) {
					if (slot.isEmpty() || slot.getCount() < slot.getMaxStackSize()) {
						if (slot.isEmpty()) {
							slotToPut = i;
							break;
						} else if (stackToPut.isEmpty() || slot.getCount() < stackToPut.getCount()) {
							slotToPut = i;
							stackToPut = slot.copy();
						}
					}
				}
			}
		}

		if (matrix != null && slotToPut > -1) {
			this.insertItem(matrix, slotToPut, toInsert);
			input.shrink(1);

			if (this.isCraftingTable()) {
				table.setResult(TableRecipeManager.getInstance().findMatchingRecipe(new TableCrafting(new EmptyContainer(), table), this.getWorld()));
			}

			this.getEnergy().extractEnergy(ModConfig.confInterfaceRFRate, false);
		} else if (this.getAutoEject() && (output.isEmpty() || StackHelper.canCombineStacks(output, toInsert))) {
			this.getInventory().insertItem(1, toInsert, false);
			input.shrink(1);
			this.getEnergy().extractEnergy(ModConfig.confInterfaceRFRate, false);
		}
	}

	public void handleOutput(ItemStack output) {
		IExtendedTable table = this.getTable();
		if (table == null) {
			return;
		}
		ItemStack result = table.getResult();
		IInventory matrix = (IInventory) table;
		if (!result.isEmpty() && (output.isEmpty() || StackHelper.canCombineStacks(output, result))) {
			if (this.getEnergy().getEnergyStored() >= ModConfig.confInterfaceRFRate) {
				ItemStack toInsert = result.copy();

				if (this.isEnderCrafter()) {
					table.setResult(ItemStack.EMPTY);
				} else {
					for (int i = 0; i < matrix.getSizeInventory(); i++) {
						ItemStack slotStack = matrix.getStackInSlot(i);
						ItemStack recipeStack = this.getRecipe().getStackInSlot(i);
						if (!recipeStack.isEmpty() && (slotStack.isEmpty() || !StackHelper.areStacksEqual(recipeStack, slotStack))) {
							return;
						}
					}

					NonNullList<ItemStack> remaining = this.getRemainingItems(matrix);

					for (int i = 0; i < remaining.size(); i++) {
						ItemStack itemstack = matrix.getStackInSlot(i);
						ItemStack itemstack1 = remaining.get(i);

						if (!itemstack.isEmpty()) {
							matrix.decrStackSize(i, 1);
							itemstack = matrix.getStackInSlot(i);
						}

						if (!itemstack1.isEmpty()) {
							if (itemstack.isEmpty()) {
								matrix.setInventorySlotContents(i, itemstack1);
							} else if (ItemStack.areItemsEqual(itemstack, itemstack1) && ItemStack.areItemStackTagsEqual(itemstack, itemstack1)) {
								itemstack1.grow(itemstack.getCount());
								matrix.setInventorySlotContents(i, itemstack1);
							}
						}
					}

					table.setResult(TableRecipeManager.getInstance().findMatchingRecipe(new TableCrafting(new EmptyContainer(), table), this.getWorld()));
				}

				this.getInventory().insertItem(1, toInsert, false);
				this.getEnergy().extractEnergy(ModConfig.confInterfaceRFRate, false);
			}
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag) {
		tag = super.writeToNBT(tag);
		tag.merge(this.inventory.serializeNBT());
		tag.merge(this.recipe.serializeNBT());
		tag.setInteger("RecipeSize", this.recipeSize);
		tag.setInteger("Energy", this.energy.getEnergyStored());
		tag.setTag("Result", this.result.serializeNBT());
		tag.setBoolean("HasRecipe", this.hasRecipe);
		tag.setInteger("AutoInsert", this.autoInsert);
		tag.setInteger("AutoExtract", this.autoExtract);
		tag.setBoolean("AutoEject", this.autoEject);
		tag.setBoolean("SmartInsert", this.smartInsert);
		return tag;
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		this.inventory.deserializeNBT(tag);
		this.recipe.deserializeNBT(tag);
		this.recipeSize = tag.getInteger("RecipeSize");
		this.energy.setEnergy(tag.getInteger("Energy"));
		this.result = new ItemStack(tag.getCompoundTag("Result"));
		this.hasRecipe = tag.getBoolean("HasRecipe");
		this.autoInsert = tag.getInteger("AutoInsert");
		this.autoExtract = tag.getInteger("AutoExtract");
		this.autoEject = tag.getBoolean("AutoEject");
		this.smartInsert = tag.getBoolean("SmartInsert");

		if (this.recipeSize == 0) {
			this.recipeSize = (int) Math.sqrt(this.recipe.getSlots());
		}
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket() {
		return new SPacketUpdateTileEntity(this.getPos(), -1, this.getUpdateTag());
	}

	@Override
	public void onDataPacket(NetworkManager manager, SPacketUpdateTileEntity packet) {
		this.readFromNBT(packet.getNbtCompound());
	}

	@Override
	public final NBTTagCompound getUpdateTag() {
		return this.writeToNBT(new NBTTagCompound());
	}

	@Override
	public void markDirty() {
		super.markDirty();
		VanillaPacketDispatcher.dispatchTEToNearbyPlayers(this);
	}

	@Override
	public int getSizeInventory() {
		return this.inventory.getSlots();
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < this.inventory.getSlots(); i++) {
			if (!this.inventory.getStackInSlot(i).isEmpty()) {
				return false;
			}
		}

		return true;
	}

	@Override
	public ItemStack getStackInSlot(int index) {
		return this.inventory.getStackInSlot(index);
	}

	@Override
	public ItemStack decrStackSize(int index, int count) {
		return index >= 0 && index < this.inventory.getSlots() && !this.inventory.getStackInSlot(index).isEmpty() && count > 0 ? this.inventory.getStackInSlot(index).splitStack(count) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeStackFromSlot(int index) {
		if (index >= 0 && index < this.inventory.getSlots()) {
			ItemStack stack = this.inventory.getStackInSlot(index);
			this.inventory.setStackInSlot(index, ItemStack.EMPTY);
			return stack;
		}
		return ItemStack.EMPTY;
	}

	@Override
	public void setInventorySlotContents(int index, ItemStack stack) {
		ItemStack itemstack = this.inventory.getStackInSlot(index);
		boolean flag = !stack.isEmpty() && stack.isItemEqual(itemstack) && ItemStack.areItemStackTagsEqual(stack, itemstack);
		this.inventory.setStackInSlot(index, stack);

		if (stack.getCount() > this.getInventoryStackLimit()) {
			stack.setCount(this.getInventoryStackLimit());
		}

		if (index == 0 && !flag) {
			this.markDirty();
		}
	}

	@Override
	public int getInventoryStackLimit() {
		return 64;
	}

	@Override
	public boolean isUsableByPlayer(EntityPlayer player) {
		return this.isUseableByPlayer(player);
	}

	@Override
	public void openInventory(EntityPlayer player) {

	}

	@Override
	public void closeInventory(EntityPlayer player) {

	}

	@Override
	public boolean isItemValidForSlot(int index, ItemStack stack) {
		return true;
	}

	@Override
	public int getField(int id) {
		return 0;
	}

	@Override
	public void setField(int id, int value) {

	}

	@Override
	public int getFieldCount() {
		return 0;
	}

	@Override
	public void clear() {

	}

	@Override
	public String getName() {
		return getDisplayName().getFormattedText();
	}

	@Override
	public boolean hasCustomName() {
		return false;
	}

	@Override
	public int[] getSlotsForFace(EnumFacing side) {
		return new int[]{0, 1};
	}

	@Override
	public boolean canInsertItem(int index, ItemStack stack, EnumFacing direction) {
		return index == 0;
	}

	@Override
	public boolean canExtractItem(int index, ItemStack stack, EnumFacing direction) {
		return index == 1;
	}

	@Override
	public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing side) {
		return this.getCapability(capability, side) != null;
	}

	@Override
	public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing side) {
		if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
			return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(new SidedInvWrapper(this, side));
		} else if (capability == CapabilityEnergy.ENERGY) {
			return CapabilityEnergy.ENERGY.cast(this.energy);
		}

		return super.getCapability(capability, side);
	}

	public IItemHandlerModifiable getInventory() {
		return this.inventory;
	}

	public ItemStackHandler getRecipe() {
		return this.recipe;
	}

	public ItemStack getResult() {
		return this.result;
	}

	public EnergyStorageCustom getEnergy() {
		return this.energy;
	}

	@Nullable
	public IExtendedTable getTable() {
		TileEntity tile = this.getWorld().getTileEntity(this.getPos().down());
		return tile instanceof IExtendedTable ? (IExtendedTable) tile : null;
	}

	public boolean hasTable() {
		IExtendedTable table = this.getTable();
		return table != null && (!this.hasRecipe() || table.getLineSize() == this.recipeSize);
	}

	public boolean isEnderCrafter() {
		return this.getTable() instanceof TileEnderCrafter;
	}

	public boolean isCraftingTable() {
		IExtendedTable table = this.getTable();
		return table instanceof TileBasicCraftingTable
				|| table instanceof TileAdvancedCraftingTable
				|| table instanceof TileEliteCraftingTable
				|| table instanceof TileUltimateCraftingTable;
	}

	public boolean hasRecipe() {
		return this.hasRecipe;
	}

	public void setHasRecipe(boolean hasRecipe) {
		this.hasRecipe = hasRecipe;
	}

	public void saveRecipe() {
		ItemStackHandler recipe = this.getRecipe();
		IExtendedTable table = this.getTable();
		if (table == null) return;
		NonNullList<ItemStack> matrix = table.getMatrix();
		recipe.setSize(matrix.size());
		for (int i = 0; i < matrix.size(); i++) {
			recipe.setStackInSlot(i, matrix.get(i).copy());
		}

		if (this.isEnderCrafter()) {
			TableCrafting crafting = new TableCrafting(new EmptyContainer(), table);
			this.result = EnderCrafterRecipeManager.getInstance().findMatchingRecipe(crafting, this.getWorld()).getCraftingResult(crafting);
		} else {
			ItemStack result = table.getResult();
			if (result != null) {
				this.result = result;
			}
		}

		this.setHasRecipe(true);
		this.recipeSize = table.getLineSize();
		this.markDirty();
	}

	public void clearRecipe() {
		ItemStackHandler recipe = this.getRecipe();
		recipe.setSize(1);
		this.result = ItemStack.EMPTY;
		this.setHasRecipe(false);
		this.markDirty();
	}

	@Nullable
	public EnumFacing getInserterFace() {
		return this.autoInsert > -1 && this.autoInsert < EnumFacing.values().length ? EnumFacing.values()[this.autoInsert] : null;
	}

	@Nullable
	public EnumFacing getExtractorFace() {
		return this.autoExtract > -1 && this.autoExtract < EnumFacing.values().length ? EnumFacing.values()[this.autoExtract] : null;
	}

	public void switchInserter() {
		if (this.autoInsert >= EnumFacing.values().length - 1) {
			this.autoInsert = -1;
		} else {
			this.autoInsert++;
			if (this.autoInsert == EnumFacing.DOWN.getIndex()) {
				this.autoInsert++;
			}
		}

		this.markDirty();
	}

	public void switchExtractor() {
		if (this.autoExtract >= EnumFacing.values().length - 1) {
			this.autoExtract = -1;
		} else {
			this.autoExtract++;
			if (this.autoExtract == EnumFacing.DOWN.getIndex()) {
				this.autoExtract++;
			}
		}

		this.markDirty();
	}

	public void disableInserter() {
		if (this.autoInsert != -1) {
			this.autoInsert = -1;
			this.markDirty();
		}
	}

	public void disableExtractor() {
		if (this.autoExtract != -1) {
			this.autoExtract = -1;
			this.markDirty();
		}
	}

	public boolean checkStackSmartly(ItemStack stack) {
		if (!this.getSmartInsert()) return true;
		if (!this.hasTable()) return false;
		if (!this.hasRecipe()) return false;

		IExtendedTable table = this.getTable();
		if (table == null) return false;
		NonNullList<ItemStack> matrix = table.getMatrix();
		for (int i = 0; i < matrix.size(); i++) {
			ItemStack slotStack = matrix.get(i);
			ItemStack recipeStack = this.getRecipe().getStackInSlot(i);
			if (StackHelper.areStacksEqual(stack, recipeStack) && (slotStack.isEmpty() || StackHelper.canCombineStacks(stack, slotStack))) {
				return true;
			}
		}

		return false;
	}

	public boolean getAutoEject() {
		return this.autoEject;
	}

	public void toggleAutoEject() {
		this.autoEject = !this.autoEject;
		this.markDirty();
	}

	public boolean getSmartInsert() {
		return this.smartInsert;
	}

	public void toggleSmartInsert() {
		this.smartInsert = !this.smartInsert;
		this.markDirty();
	}

	public boolean isUseableByPlayer(EntityPlayer player) {
		return this.getWorld().getTileEntity(this.getPos()) == this && player.getDistanceSq(this.getPos().add(0.5, 0.5, 0.5)) <= 64;
	}

	private void insertItem(IInventory matrix, int slot, ItemStack stack) {
		ItemStack slotStack = matrix.getStackInSlot(slot);
		if (slotStack.isEmpty()) {
			matrix.setInventorySlotContents(slot, stack);
		} else {
			if (StackHelper.areStacksEqual(stack, slotStack) && slotStack.getCount() < slotStack.getMaxStackSize()) {
				ItemStack newStack = slotStack.copy();
				int newSize = Math.min(slotStack.getCount() + stack.getCount(), slotStack.getMaxStackSize());
				newStack.setCount(newSize);
				matrix.setInventorySlotContents(slot, newStack);
			}
		}

	}

	private NonNullList<ItemStack> getRemainingItems(IInventory matrix) {
		NonNullList<ItemStack> ret = NonNullList.withSize(matrix.getSizeInventory(), ItemStack.EMPTY);
		for (int i = 0; i < ret.size(); i++) {
			ret.set(i, ForgeHooks.getContainerItem(matrix.getStackInSlot(i)));
		}

		return ret;
	}

	class StackHandler extends ItemStackHandler {

		StackHandler() {
			super(2);
		}

		@Override
		public void onContentsChanged(int slot) {
			TileAutomationInterface.this.markDirty();
		}
	}

	@Nonnull
	@Override
	public ITextComponent getDisplayName() {
		return new TextComponentTranslation("tile.ec.interface.name");
	}
}
