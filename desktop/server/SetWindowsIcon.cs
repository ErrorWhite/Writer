using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

internal static class SetWindowsIcon
{
    private const int IconImage = 3;
    private const int IconGroup = 14;
    private const ushort EnglishUnitedStates = 1033;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr BeginUpdateResource(string path, bool deleteExistingResources);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr id, ushort language, byte[] image, uint size);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool EndUpdateResource(IntPtr update, bool discard);

    private static int Main(string[] args)
    {
        if (args.Length != 2) return 2;
        var update = BeginUpdateResource(args[0], false);
        if (update == IntPtr.Zero) throw new Win32Exception();
        var discard = true;
        try
        {
            using (var source = new BinaryReader(File.OpenRead(args[1])))
            using (var group = new MemoryStream())
            using (var directory = new BinaryWriter(group))
            {
                var reserved = source.ReadUInt16();
                var type = source.ReadUInt16();
                var count = source.ReadUInt16();
                if (reserved != 0 || type != 1 || count == 0) throw new InvalidDataException("Invalid ICO directory.");
                directory.Write(reserved);
                directory.Write(type);
                directory.Write(count);
                for (ushort index = 1; index <= count; index++)
                {
                    var entry = source.ReadBytes(12);
                    var length = BitConverter.ToInt32(entry, 8);
                    var offset = source.ReadUInt32();
                    var next = source.BaseStream.Position;
                    if (length <= 0 || (long)offset + length > source.BaseStream.Length) throw new InvalidDataException("Truncated ICO image.");
                    source.BaseStream.Position = offset;
                    WriteResource(update, IconImage, index, source.ReadBytes(length));
                    source.BaseStream.Position = next;
                    directory.Write(entry);
                    directory.Write(index);
                }
                // The pinned Electron executable uses group 1 for its Explorer icon.
                WriteResource(update, IconGroup, 1, group.ToArray());
            }
            discard = false;
        }
        finally
        {
            if (!EndUpdateResource(update, discard) && !discard) throw new Win32Exception();
        }
        return 0;
    }

    private static void WriteResource(IntPtr update, int type, int id, byte[] data)
    {
        if (!UpdateResource(update, (IntPtr)type, (IntPtr)id, EnglishUnitedStates, data, (uint)data.Length)) throw new Win32Exception();
    }
}
